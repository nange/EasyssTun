package com.easysstun

import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 守护服务的配置前置条件：失败经 [VpnController] 上报并自行停止，但绝不写 [Pref.userWantsVpn]——持久化意图属于用户。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class EasyssVpnServiceConfigTest {

    private lateinit var service: EasyssVpnService

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext()
        ).edit().clear().commit()
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        service = Robolectric.buildService(EasyssVpnService::class.java).get()
    }

    @After
    fun tearDown() {
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        PreferenceManager.getDefaultSharedPreferences(service).edit().clear().commit()
    }

    @Test
    fun startWithoutProfile_reportsFailureAndStopsWithoutTouchingTheIntent() {
        val pref = Pref(service)
        pref.userWantsVpn = true

        val result = service.onStartCommand(EasyssVpnService.connectIntent(service), 0, 1)

        assertEquals(
            "a start that cannot run must not be restarted by the system",
            android.app.Service.START_NOT_STICKY,
            result
        )
        assertTrue(
            "the service must not clear the user's intent on a failed start",
            pref.userWantsVpn,
        )
        assertTrue(
            "a start without an active profile must stop the service instance",
            shadowOf(service).isStoppedBySelf,
        )
        val state = VpnController.get(service).state.value
        assertTrue("the failure must reach the UI instead of staying 'connecting'", state is VpnState.Failed)
        assertEquals(
            service.getString(R.string.error_no_active_profile),
            (state as VpnState.Failed).message
        )
    }

    @Test
    fun disconnectWhileIdle_stopsTheResidualServiceInstance() {
        // 从未启动过（pref 未初始化）：空闲的 ACTION_DISCONNECT 也必须 finalize 残留实例。
        val result = service.onStartCommand(
            Intent(EasyssVpnService.ACTION_DISCONNECT),
            0,
            1,
        )

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(
            "an idle disconnect must finalize the service instance",
            shadowOf(service).isStoppedBySelf,
        )
        assertEquals(VpnState.Idle, VpnController.get(service).state.value)
        assertFalse(TunnelState.ready.value)
    }
}
