package com.easysstun

import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowVpnService

/** 首页只渲染 [VpnState]：用例直接驱动进程级 [VpnController] 的状态上报，断言用户所见。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MainFragmentStateTest {

    private lateinit var context: Context
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var vpn: VpnController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        ShadowVpnService.setPrepareResult(null)
        vpn = VpnController.get(context)
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = activityController.get()
    }

    @After
    fun tearDown() {
        activityController.destroy()
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun mainFragment(): MainFragment {
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        return navHost.childFragmentManager.primaryNavigationFragment as MainFragment
    }

    private fun button(): MaterialButton =
        mainFragment().requireView().findViewById(R.id.service_button)

    private fun spinner(): Spinner =
        mainFragment().requireView().findViewById(R.id.server_spinner)

    private fun statsGrid(): LinearLayout =
        mainFragment().requireView().findViewById(R.id.stats_grid)

    private fun serviceTitle(): String =
        mainFragment().requireView()
            .findViewById<android.widget.TextView>(R.id.service_title)
            .text
            .toString()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun recreate() {
        activityController.recreate()
        activity = activityController.get()
        idle()
    }

    private fun addProfile(name: String, id: String = name): Profile {
        val profile = Profile(
            id = id,
            name = name,
            server = "example.com",
            serverPort = 443,
            password = "secret",
        )
        ProfileStore.get(context).add(profile)
        return profile
    }

    @Test
    fun noProfile_showsDisabledStoppedState() {
        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertFalse(button().isEnabled)
        assertEquals(View.GONE, statsGrid().visibility)
    }

    @Test
    fun start_entersConnectingStateUntilServiceReportsRunning() {
        val profile = addProfile("A")
        ProfileStore.get(context).select(profile.id)
        // 启动后再添加的配置档需重建视图才能被读取。
        recreate()

        button().performClick()
        idle()

        assertEquals(
            activity.getString(R.string.service_connecting),
            serviceTitle()
        )
        assertTrue(
            "connecting must stay cancellable from the home screen",
            button().isEnabled
        )
        assertEquals(activity.getString(R.string.service_cancel), button().text.toString())

        vpn.onServiceRunning(profile.id)
        idle()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.VISIBLE, statsGrid().visibility)
    }

    @Test
    fun cancelWhileConnecting_returnsToStoppedState() {
        val profile = addProfile("A")
        ProfileStore.get(context).select(profile.id)
        recreate()

        // 通过同一个按钮完成开始与取消，走真实入口。
        button().performClick()
        idle()
        assertEquals(activity.getString(R.string.service_cancel), button().text.toString())

        button().performClick()
        idle()
        assertEquals(
            activity.getString(R.string.service_stopping),
            serviceTitle()
        )
        assertFalse("stopping is not cancellable", button().isEnabled)

        vpn.onServiceStopped()
        idle()

        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.GONE, statsGrid().visibility)
    }

    @Test
    fun connecting_recoversWhenTheServiceIsDestroyed() {
        val profile = addProfile("A")
        ProfileStore.get(context).select(profile.id)
        recreate()

        button().performClick()
        idle()
        assertEquals(activity.getString(R.string.service_cancel), button().text.toString())

        // 启动途中服务被系统回收：界面不能卡在连接中且无路可退。
        vpn.onServiceDestroyed()
        idle()

        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)
    }

    @Test
    fun switchingServer_activatesTheNewProfileAndRebuildsTheTunnel() {
        val first = addProfile("A")
        val second = addProfile("B")
        ProfileStore.get(context).select(first.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(first.id)
        recreate()

        spinner().setSelection(1)
        idle()

        // 切换时目标配置档立即置为 active（进程被杀也能续上），旧隧道先拆除。
        assertEquals(activity.getString(R.string.service_switching), serviceTitle())
        assertEquals(activity.getString(R.string.service_cancel), button().text.toString())
        assertTrue("a switch can be cancelled", button().isEnabled)
        assertFalse("the spinner is disabled while switching", spinner().isEnabled)
        assertEquals("B", ProfileStore.get(context).active()?.name)

        // 旧隧道停止后，切换继续用新配置档连接。
        vpn.onServiceStopped()
        idle()
        assertEquals("B", ProfileStore.get(context).active()?.name)
        assertEquals(activity.getString(R.string.service_connecting), serviceTitle())

        vpn.onServiceRunning(second.id)
        idle()
        assertTrue("the spinner is usable again after the switch", spinner().isEnabled)
        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
    }

    @Test
    fun rotationDuringSwitch_keepsTheSwitchingState() {
        val first = addProfile("A")
        val second = addProfile("B")
        ProfileStore.get(context).select(first.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(first.id)
        recreate()

        spinner().setSelection(1)
        idle()

        // 待切换目标存放在 [VpnState] 中，旋转后仍能恢复。
        recreate()
        assertEquals(activity.getString(R.string.service_switching), serviceTitle())
        assertFalse(spinner().isEnabled)

        vpn.onServiceStopped()
        idle()
        assertEquals("B", ProfileStore.get(context).active()?.name)

        vpn.onServiceRunning(second.id)
        idle()
        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(spinner().isEnabled)
    }

    @Test
    fun rotation_whileRunning_keepsRunningUi() {
        val profile = addProfile("A")
        ProfileStore.get(context).select(profile.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(profile.id)
        recreate()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)

        recreate()

        assertEquals(
            "recreating the activity must not drop the running state",
            activity.getString(R.string.service_disable),
            button().text.toString()
        )
    }

    @Test
    fun startFailure_showsTheErrorOnceAndReturnsToStopped() {
        val profile = addProfile("A")
        ProfileStore.get(context).select(profile.id)
        recreate()

        button().performClick()
        idle()

        vpn.onServiceFailed("dial tcp 1.2.3.4:443: connection refused", profile.id)
        idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("a failed start must explain itself", dialog)
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)

        // 旋转后不得再次弹出同一个失败对话框。
        recreate()
        assertSame("the failure must be reported only once", dialog, ShadowDialog.getLatestDialog())
    }
}
