package com.easysstun

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easysstun.databinding.FragmentMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class StatsBinderTest {

    // Material widgets need the app theme; the Robolectric application context
    // does not carry it.
    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme,
    )

    private fun binding(): FragmentMainBinding =
        FragmentMainBinding.inflate(LayoutInflater.from(context))

    @Test
    fun render_formatsEveryField() {
        val binding = binding()
        val binder = StatsBinder(binding)

        binder.render(
            StatsSnapshot(
                avgRttMs = 12.34,
                conns = 7,
                bytesSent = 1_500,
                bytesRecv = 2_500_000,
                priorityConns = 3,
                bulkConns = 4,
                priorityActiveStreams = 2,
                bulkActiveStreams = 5,
                uptimeSeconds = 3661.0,
                activeStreams = 9,
                slotRetiredDegraded = 1,
                connRotated = 6,
                slotProbeSlow = 2,
                downloadSpeed = "1.5 MB/s",
                uploadSpeed = "2.0 KB/s",
                peakDownloadSpeed = "10 MB/s",
                peakUploadSpeed = "5 MB/s",
            )
        )

        assertEquals(View.VISIBLE, binding.statsGrid.visibility)
        assertEquals("12.3ms", binding.statsAvgRttMs.text.toString())
        assertEquals("1.5 M/s", binding.statsDownloadSpeed.text.toString())
        assertEquals("2.0 K/s", binding.statsUploadSpeed.text.toString())
        assertEquals("7", binding.statsConns.text.toString())
        assertEquals("1.50K", binding.statsBytesSent.text.toString())
        assertEquals("2.50M", binding.statsBytesRecv.text.toString())
        assertEquals("3", binding.statsPriorityConns.text.toString())
        assertEquals("4", binding.statsBulkConns.text.toString())
        assertEquals("2", binding.statsPriorityActiveStreams.text.toString())
        assertEquals("5", binding.statsBulkActiveStreams.text.toString())
        assertEquals("9", binding.statsActiveStreams.text.toString())
        assertEquals("1", binding.statsSlotRetiredDegraded.text.toString())
        assertEquals("6", binding.statsConnRotated.text.toString())
        assertEquals("2", binding.statsSlotProbeSlow.text.toString())
        assertEquals("10 M/s", binding.statsPeakDownloadSpeed.text.toString())
        assertEquals("5 M/s", binding.statsPeakUploadSpeed.text.toString())
        assertEquals("1h 1m 1s", binding.statsUptime.text.toString())
    }

    @Test
    fun renderUnavailable_showsStatusAndClearsStaleValues() {
        val binding = binding()
        val binder = StatsBinder(binding)
        binder.render(StatsSnapshot(avgRttMs = 99.0, conns = 42, uptimeSeconds = 10.0))

        binder.renderUnavailable()

        assertEquals(
            context.getString(R.string.stats_unavailable),
            binding.statsAvgRttMs.text.toString(),
        )
        assertEquals("", binding.statsConns.text.toString())
        assertEquals("", binding.statsUptime.text.toString())
        assertEquals("", binding.statsDownloadSpeed.text.toString())
    }

    @Test
    fun setVisible_togglesTheWholeGridButKeepsTheHeader() {
        val binding = binding()
        val binder = StatsBinder(binding)

        binder.setVisible(false)
        assertEquals(View.GONE, binding.statsGrid.visibility)
        assertEquals(View.VISIBLE, binding.statsHeader.visibility)

        binder.setVisible(true)
        assertEquals(View.VISIBLE, binding.statsGrid.visibility)
    }

    @Test
    fun expansion_togglesTheExpandableSection() {
        val binding = binding()
        val binder = StatsBinder(binding)
        binder.setVisible(true)

        assertFalse(binder.isExpanded)
        assertEquals(View.GONE, binding.statsExpandable.visibility)

        binding.statsHeader.performClick()
        assertTrue(binder.isExpanded)
        assertEquals(View.VISIBLE, binding.statsExpandable.visibility)

        binding.statsHeader.performClick()
        assertFalse(binder.isExpanded)
        assertEquals(View.GONE, binding.statsExpandable.visibility)
    }

    @Test
    fun expansion_isRestoredWhenCreatedExpanded() {
        val binding = binding()

        val binder = StatsBinder(binding, initiallyExpanded = true)

        assertTrue(binder.isExpanded)
        assertEquals(View.VISIBLE, binding.statsExpandable.visibility)
        assertEquals(180f, binding.statsHeaderArrow.rotation, 0.0f)
    }
}
