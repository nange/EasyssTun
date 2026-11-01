package com.easysstun

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
// Robolectric 会读 App manifest（二进制资源已启用），必须显式钉住 sdk，否则 targetSdk 37 不被支持。
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UpdateManagerTest {

    private companion object {
        const val NOW = 1_000_000L
    }

    private lateinit var context: Context
    private lateinit var pref: Pref

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        pref = Pref(context)
    }

    @After
    fun tearDown() {
        UpdateInstaller.cleanup(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun release(
        tag: String = "v3.0.0-rc20",
        versionName: String = "3.0.0-rc20",
        versionCode: Int = 170,
        abi: String = "arm64-v8a",
    ): Release {
        val assetName = "EasyssTun_v${versionName}_${versionCode}_${abi}_release_20261030.apk"
        return Release(
            tagName = tag,
            body = "release notes",
            htmlUrl = "https://github.com/nange/EasyssTun/releases/tag/$tag",
            assets = listOf(
                ReleaseAsset(
                    name = assetName,
                    downloadUrl = "https://github.com/nange/EasyssTun/releases/download/$tag/$assetName",
                    size = 16,
                    digest = "sha256:" + "ab".repeat(32),
                ),
            ),
        )
    }

    private class FakeSource(
        var release: Release? = null,
        var fetchError: UpdateException? = null,
        var downloadError: UpdateException? = null,
        var downloadBytes: ByteArray = ByteArray(16),
        val gate: CompletableDeferred<Unit>? = null,
        /** 挂起下一次 fetch，便于在检查进行中操作。 */
        var fetchGate: CompletableDeferred<Unit>? = null,
    ) : UpdateSource {
        var fetchCount = 0
        var downloadCount = 0

        override suspend fun fetchLatestRelease(): Release {
            fetchCount++
            // 先取走结果再挂起：测试可能在本调用停在 gate 上时改 [fetchError]。
            val error = fetchError
            val gate = fetchGate.also { fetchGate = null }
            gate?.await()
            error?.let { throw it }
            return release ?: throw UpdateException(UpdateError.Network)
        }

        override suspend fun download(
            asset: ApkAsset,
            dest: File,
            onProgress: (Long, Long) -> Unit,
        ) {
            downloadCount++
            dest.parentFile?.mkdirs()
            dest.writeBytes(downloadBytes)
            onProgress(downloadBytes.size.toLong(), downloadBytes.size.toLong())
            downloadError?.let { throw it }
            gate?.await()
        }
    }

    private fun manager(
        source: UpdateSource,
        scope: CoroutineScope,
        isDebug: Boolean = false,
        tunnelReady: StateFlow<Boolean> = MutableStateFlow(true),
        verifier: (File, Int) -> InstallCheck = { _, _ -> InstallCheck.Ok },
    ): UpdateManager = UpdateManager(
        UpdateEnvironment(
            context = context,
            pref = pref,
            scope = scope,
            sourceFactory = { source },
            verifier = verifier,
            clock = { NOW },
            isDebugBuild = isDebug,
            currentVersionCode = 169,
            currentVersionName = "3.0.0-rc19.4",
            supportedAbis = listOf("arm64-v8a"),
            tunnelReady = tunnelReady,
        )
    )

    @Test
    fun autoCheckIsSkippedInDebugBuilds() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this, isDebug = true)

        manager.autoCheck()
        advanceUntilIdle()

        assertEquals(0, source.fetchCount)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun autoCheckIsSkippedWhenDisabled() = runTest {
        pref.updateAutoCheck = false
        val source = FakeSource(release = release())
        val manager = manager(source, this)

        manager.autoCheck()
        advanceUntilIdle()

        assertEquals(0, source.fetchCount)
    }

    @Test
    fun autoCheckIsThrottledForOneDay() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        pref.updateLastCheckAt = NOW

        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(0, source.fetchCount)

        pref.updateLastCheckAt = NOW - AUTO_CHECK_INTERVAL_MS - 1
        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun autoCheckDoesNotRetryAfterFailureWithinProcess() = runTest {
        val source = FakeSource(fetchError = UpdateException(UpdateError.Network))
        val manager = manager(source, this)

        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Failed)

        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun autoCheckWaitsForTheTunnelInsteadOfGoingDirect() = runTest {
        val source = FakeSource(release = release())
        // backgroundScope：宽限期任务在测试结束时直接丢弃，不等待。
        val manager = manager(source, backgroundScope, tunnelReady = MutableStateFlow(false))

        manager.autoCheck()
        runCurrent()

        assertEquals("a cold start must not probe a blocked network directly", 0, source.fetchCount)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun autoCheckFallsBackToDirectWhenTheTunnelNeverComesUp() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this, tunnelReady = MutableStateFlow(false))

        manager.autoCheck()
        runCurrent()
        assertEquals(0, source.fetchCount)

        advanceTimeBy(LAUNCH_FALLBACK_DELAY_MS + 1)
        advanceUntilIdle()

        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun tunnelReadinessRunsTheDeferredCheck() = runTest {
        val source = FakeSource(release = release())
        val tunnelReady = MutableStateFlow(false)
        val manager = manager(source, this, tunnelReady = tunnelReady)

        manager.autoCheck()
        runCurrent()
        assertEquals(0, source.fetchCount)

        tunnelReady.value = true
        manager.onTunnelReady()
        advanceUntilIdle()

        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)

        // 宽限期的兜底检查已被取代，不能再检查一次。
        advanceTimeBy(LAUNCH_FALLBACK_DELAY_MS + 1)
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun tunnelReadinessBypassesTheFailureRetryThrottle() = runTest {
        val source = FakeSource(fetchError = UpdateException(UpdateError.Network))
        val manager = manager(source, this)

        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Failed)

        // 第二次启动检查仍受节流限制。
        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)

        // 隧道就绪则立即经代理重试。
        source.fetchError = null
        source.release = release()
        manager.onTunnelReady()
        advanceUntilIdle()

        assertEquals(2, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun tunnelReadinessDuringAnInFlightCheckRetriesAfterwards() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(
            release = release(),
            fetchError = UpdateException(UpdateError.Network),
            fetchGate = gate,
        )
        val tunnelReady = MutableStateFlow(false)
        val manager = manager(source, this, tunnelReady = tunnelReady)

        // 宽限期先发了一次直连检查，隧道在它未完成时起来；该就绪事件不能被丢弃。
        manager.autoCheck()
        advanceTimeBy(LAUNCH_FALLBACK_DELAY_MS + 1)
        runCurrent()
        assertEquals(1, source.fetchCount)

        tunnelReady.value = true
        manager.onTunnelReady()
        source.fetchError = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun queuedRetryIsDroppedWhenTheTunnelGoesAway() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(
            release = release(),
            fetchError = UpdateException(UpdateError.Network),
            fetchGate = gate,
        )
        val tunnelReady = MutableStateFlow(false)
        val manager = manager(source, this, tunnelReady = tunnelReady)

        manager.autoCheck()
        advanceTimeBy(LAUNCH_FALLBACK_DELAY_MS + 1)
        runCurrent()
        assertEquals(1, source.fetchCount)

        tunnelReady.value = true
        manager.onTunnelReady()
        // 检查未完成前 VPN 又断开：此时排队的重试会变成绕过节流的直连检查，必须丢弃。
        tunnelReady.value = false
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("no direct retry once the tunnel is gone", 1, source.fetchCount)
    }

    @Test
    fun observeTunnelRunsTheDeferredCheck() = runTest {
        val source = FakeSource(release = release())
        val tunnelReady = MutableStateFlow(false)
        val manager = manager(source, this, tunnelReady = tunnelReady)
        val watcher = manager.observeTunnel()

        manager.autoCheck()
        runCurrent()
        assertEquals(0, source.fetchCount)

        tunnelReady.value = true
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.Available)

        watcher.cancel()
    }

    @Test
    fun tunnelReadinessDoesNotRecheckWithinADay() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)

        manager.autoCheck()
        advanceUntilIdle()
        assertEquals(1, source.fetchCount)

        manager.onTunnelReady()
        advanceUntilIdle()

        assertEquals(1, source.fetchCount)
    }

    @Test
    fun manualCheckBypassesThrottle() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        pref.updateLastCheckAt = NOW

        manager.checkNow()
        advanceUntilIdle()

        assertEquals(1, source.fetchCount)
        assertEquals(NOW, pref.updateLastCheckAt)
    }

    @Test
    fun newerVersionIsOffered() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        val state = manager.state.value as UpdateState.Available
        assertEquals(170, state.release.versionCode)
        assertEquals("3.0.0-rc20", state.release.versionName)
        assertEquals("v3.0.0-rc20", state.release.tag)
        assertEquals(170, (manager.state.value as UpdateState.Available).release.versionCode)
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        val source = FakeSource(release = release(tag = "v3.0.0-rc19.4", versionName = "3.0.0-rc19.4", versionCode = 169))
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        assertTrue(manager.state.value is UpdateState.UpToDate)
    }

    @Test
    fun releaseWithMismatchedAssetNameIsRejected() = runTest {
        val source = FakeSource(
            release = release(tag = "v3.0.0-rc20", versionName = "3.0.0-rc19.4", versionCode = 170),
        )
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.MetadataMismatch, failed.error)
        assertEquals(UpdateAction.CHECK, failed.action)
    }

    @Test
    fun inconsistentVersionCodeAndNameIsRejected() = runTest {
        val source = FakeSource(release = release(versionCode = 169))
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.MetadataMismatch, failed.error)
    }

    @Test
    fun releaseWithoutCompatibleAbiIsRejected() = runTest {
        val source = FakeSource(release = release(abi = "x86_64"))
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.NoCompatibleAsset, failed.error)
    }

    @Test
    fun networkFailureIsReported() = runTest {
        val source = FakeSource(fetchError = UpdateException(UpdateError.Network))
        val manager = manager(source, this)

        manager.checkNow()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.Network, failed.error)
        assertTrue(failed.manual)
    }

    @Test
    fun downloadPublishesVerifiedFile() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        advanceUntilIdle()

        val ready = manager.state.value as UpdateState.ReadyToInstall
        assertEquals(1, source.downloadCount)
        assertTrue(ready.file.isFile)
        assertEquals(16L, ready.file.length())
        assertEquals(170, ready.release.versionCode)

        manager.markInstallLaunched()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertTrue("the verified APK stays cached for the installer", ready.file.isFile)
    }

    @Test
    fun recheckKeepsDownloadedUpdateFile() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()
        manager.downloadAndInstall()
        advanceUntilIdle()
        val file = (manager.state.value as UpdateState.ReadyToInstall).file

        manager.checkNow()
        advanceUntilIdle()

        assertTrue(manager.state.value is UpdateState.Available)
        assertTrue("a check must not delete a downloaded update", file.isFile)
    }

    @Test
    fun downloadRejectsForeignSigner() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this) { _, _ -> InstallCheck.SignerMismatch }
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.SignerMismatch, failed.error)
        assertEquals(UpdateAction.DOWNLOAD, failed.action)
        assertTrue(UpdateInstaller.updateDir(context).listFiles().isNullOrEmpty())
    }

    @Test
    fun downloadFailureDeletesPartialFile() = runTest {
        val source = FakeSource(
            release = release(),
            downloadError = UpdateException(UpdateError.ChecksumMismatch),
        )
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateError.ChecksumMismatch, failed.error)
        assertTrue(UpdateInstaller.updateDir(context).listFiles().isNullOrEmpty())
    }

    @Test
    fun downloadingStateCarriesTheRelease() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(release = release(), gate = gate)
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        runCurrent()

        val downloading = manager.state.value as UpdateState.Downloading
        assertEquals(170, downloading.release.versionCode)
        assertEquals("3.0.0-rc20", downloading.release.versionName)

        manager.cancelDownload()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun downloadFailureKeepsTheReleaseForRetry() = runTest {
        val source = FakeSource(
            release = release(),
            downloadError = UpdateException(UpdateError.Network),
        )
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        advanceUntilIdle()
        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateAction.DOWNLOAD, failed.action)
        assertEquals(170, failed.release?.versionCode)

        // 重试继续下载同一个 release，不重新检查：失败状态自带 release 即可判断。
        source.downloadError = null
        manager.retryLastAction()
        advanceUntilIdle()

        assertEquals(2, source.downloadCount)
        assertEquals(1, source.fetchCount)
        assertTrue(manager.state.value is UpdateState.ReadyToInstall)
    }

    @Test
    fun checkFailureHasNoReleaseToRetry() = runTest {
        val source = FakeSource(fetchError = UpdateException(UpdateError.Network))
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateAction.CHECK, failed.action)
        assertNull(failed.release)
    }

    @Test
    fun cancelDownloadReturnsToIdleAndCleansUp() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(release = release(), gate = gate)
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()

        manager.downloadAndInstall()
        runCurrent()
        assertEquals(1, source.downloadCount)

        manager.cancelDownload()
        advanceUntilIdle()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertTrue(UpdateInstaller.updateDir(context).listFiles().isNullOrEmpty())
    }

    @Test
    fun consumePromptOnlyOncePerVersion() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()
        val versionCode = (manager.state.value as UpdateState.Available).release.versionCode

        assertTrue(manager.consumePrompt(versionCode))
        assertFalse(manager.consumePrompt(versionCode))
    }

    @Test
    fun skippedVersionIsNeverPrompted() = runTest {
        val source = FakeSource(release = release())
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()
        val versionCode = (manager.state.value as UpdateState.Available).release.versionCode

        manager.dismissVersion(versionCode)

        assertEquals(versionCode, pref.updateDismissedVersionCode)
        assertFalse(manager.consumePrompt(versionCode))
    }

    @Test
    fun retryRerunsFailedCheck() = runTest {
        val source = FakeSource(
            release = release(),
            fetchError = UpdateException(UpdateError.Network),
        )
        val manager = manager(source, this)
        manager.checkNow()
        advanceUntilIdle()
        assertTrue(manager.state.value is UpdateState.Failed)

        source.fetchError = null
        manager.retryLastAction()
        advanceUntilIdle()

        assertTrue(manager.state.value is UpdateState.Available)
    }
}
