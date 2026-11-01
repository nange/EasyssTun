package com.easysstun

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 守护「Intent 只带 action」契约：服务与 UI 同进程，连接配置一律从 Pref 读取，不得序列化进 Intent。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class EasyssVpnServiceIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun connectIntent_targetsTheServiceAndCarriesOnlyTheAction() {
        val intent = EasyssVpnService.connectIntent(context)

        assertEquals(EasyssVpnService.ACTION_CONNECT, intent.action)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(EasyssVpnService::class.java.name, intent.component?.className)
        assertNull("configuration must come from Pref, not Intent extras", intent.extras)
    }

    @Test
    fun disconnectIntent_targetsTheServiceAndCarriesOnlyTheAction() {
        val intent = EasyssVpnService.disconnectIntent(context)

        assertEquals(EasyssVpnService.ACTION_DISCONNECT, intent.action)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(EasyssVpnService::class.java.name, intent.component?.className)
        assertNull("disconnect must not carry configuration extras", intent.extras)
    }
}
