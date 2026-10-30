package com.easysstun

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UpdateInstallerTest {

    private lateinit var context: Context
    private lateinit var updateDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        updateDir = UpdateInstaller.updateDir(context)
        updateDir.mkdirs()
        updateDir.listFiles()?.forEach { it.delete() }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
    }

    private fun apkFile(name: String = "update.apk"): File =
        File(updateDir, name).apply { writeBytes(ByteArray(32)) }

    @Test
    fun updateDirLivesInCache() {
        assertTrue(updateDir.absolutePath.startsWith(context.cacheDir.absolutePath))
    }

    @Test
    fun canInstallPackagesReflectsSystemPermission() {
        assertFalse(UpdateInstaller.canInstallPackages(context))
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(UpdateInstaller.canInstallPackages(context))
    }

    @Test
    fun unknownSourcesIntentTargetsThisPackage() {
        val intent = UpdateInstaller.unknownSourcesSettingsIntent(context)
        assertEquals("package:${context.packageName}", intent.dataString)
        assertTrue(
            intent.action == android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES ||
                intent.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
    }

    @Test
    fun installIntentUsesFileProviderAndApkMimeType() {
        val apk = apkFile()
        val intent = UpdateInstaller.installIntent(context, apk)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", intent.data?.authority)
    }

    @Test
    fun verifyRejectsMissingFile() {
        val missing = File(updateDir, "missing.apk")
        assertEquals(InstallCheck.InvalidApk, UpdateInstaller.verify(context, missing, 170))
    }

    @Test
    fun verifyRejectsUnparsableArchive() {
        val garbage = apkFile("garbage.apk")
        assertEquals(InstallCheck.InvalidApk, UpdateInstaller.verify(context, garbage, 170))
    }

    @Test
    fun verifyRejectsForeignPackage() {
        val apk = apkFile()
        val archive = PackageInfo().apply {
            packageName = "com.example.other"
            longVersionCode = 170
        }

        assertEquals(
            InstallCheck.WrongPackage,
            UpdateInstaller.verify(context, apk, 170) { archive },
        )
    }

    @Test
    fun verifyRejectsUnexpectedVersionCode() {
        val apk = apkFile()
        val archive = PackageInfo().apply {
            packageName = BuildConfig.APPLICATION_ID
            longVersionCode = 171
        }

        assertEquals(
            InstallCheck.VersionMismatch,
            UpdateInstaller.verify(context, apk, 170) { archive },
        )
    }

    @Test
    fun verifyWithoutSigningInfoIsNotInstallable() {
        val apk = apkFile()
        val archive = PackageInfo().apply {
            packageName = BuildConfig.APPLICATION_ID
            longVersionCode = 170
        }

        assertEquals(
            InstallCheck.InvalidApk,
            UpdateInstaller.verify(context, apk, 170) { archive },
        )
    }

    @Test
    fun cleanupRemovesApksAndPartialDownloads() {
        val apk = apkFile()
        val part = apkFile("update.apk.part")
        val keep = File(updateDir, "keep.txt").apply { writeText("keep") }

        UpdateInstaller.cleanup(context)

        assertFalse(apk.exists())
        assertFalse(part.exists())
        assertTrue(keep.exists())
    }
}
