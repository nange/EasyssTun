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
 * Guards the Start-failure notification contract introduced when the
 * libeasyss AAR's Go export changed to `func Start(cfg) error`: the
 * gomobile binding now throws Exception from Mobile.start(), and
 * TProxyService must broadcast the specific error text so the UI can show
 * it in a dialog (MainFragment shows it on ACTION_SERVICE_START_FAILED).
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class TProxyServiceStartFailureTest {

    private fun newService(): TProxyService =
        Robolectric.buildService(TProxyService::class.java).get()

    private fun startFailedBroadcasts(service: TProxyService): List<Intent> {
        return shadowOf(service).broadcastIntents
            .filter { it.action == TProxyService.ACTION_SERVICE_START_FAILED }
    }

    @Test
    fun notifyStartFailure_sendsBroadcastWithActionAndError() {
        val service = newService()
        service.notifyStartFailure("dial tcp 1.2.3.4:443: connection refused")

        val broadcasts = startFailedBroadcasts(service)
        assertTrue("expected a SERVICE_START_FAILED broadcast", broadcasts.isNotEmpty())
        val intent = broadcasts.last()
        assertEquals(TProxyService.ACTION_SERVICE_START_FAILED, intent.action)
        assertEquals(
            "dial tcp 1.2.3.4:443: connection refused",
            intent.getStringExtra(TProxyService.EXTRA_START_ERROR)
        )
        assertEquals("broadcast must be scoped to this package", service.packageName, intent.`package`)
    }

    @Test
    fun notifyStartFailure_passesErrorMessageVerbatim() {
        val service = newService()
        service.notifyStartFailure("already started, call Stop first")

        val broadcasts = startFailedBroadcasts(service)
        assertTrue("expected a SERVICE_START_FAILED broadcast", broadcasts.isNotEmpty())
        assertEquals(
            "already started, call Stop first",
            broadcasts.last().getStringExtra(TProxyService.EXTRA_START_ERROR)
        )
    }
}
