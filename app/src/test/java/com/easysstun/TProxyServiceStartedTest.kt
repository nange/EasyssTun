package com.easysstun

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the started-notification contract introduced alongside the
 * connecting state: TProxyService must broadcast ACTION_SERVICE_STARTED
 * once startup fully completes so MainFragment can leave the "connecting"
 * (spinner + disabled button) state and show the running "Stop" state.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class TProxyServiceStartedTest {

    private fun newService(): TProxyService =
        Robolectric.buildService(TProxyService::class.java).get()

    private fun startedBroadcasts(service: TProxyService): List<Intent> {
        return shadowOf(service).broadcastIntents
            .filter { it.action == TProxyService.ACTION_SERVICE_STARTED }
    }

    @Test
    fun notifyServiceStarted_sendsBroadcastWithActionScopedToPackage() {
        val service = newService()
        service.notifyServiceStarted()

        val broadcasts = startedBroadcasts(service)
        assertTrue("expected a SERVICE_STARTED broadcast", broadcasts.isNotEmpty())
        val intent = broadcasts.last()
        assertEquals(TProxyService.ACTION_SERVICE_STARTED, intent.action)
        assertEquals("broadcast must be scoped to this package", service.packageName, intent.`package`)
    }

    @Test
    fun notifyServiceStarted_doesNotCarryErrorExtra() {
        val service = newService()
        service.notifyServiceStarted()

        val broadcasts = startedBroadcasts(service)
        assertTrue("expected a SERVICE_STARTED broadcast", broadcasts.isNotEmpty())
        assertEquals(
            "started broadcast must not carry a start error",
            null,
            broadcasts.last().getStringExtra(TProxyService.EXTRA_START_ERROR)
        )
    }
}
