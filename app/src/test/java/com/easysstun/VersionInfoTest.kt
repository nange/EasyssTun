package com.easysstun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the startup version banner ([versionBanner]) that libeasyss itself
 * does not print through its gomobile binding.
 */
class VersionInfoTest {

    @Test
    fun versionBanner_reportsAppAndLibeasyssVersion() {
        val banner = versionBanner()

        assertTrue(banner.startsWith("EasyssTun v${BuildConfig.VERSION_NAME} ("))
        assertTrue(banner.contains("| libeasyss "))
        // Some version must follow the label (the native library usually cannot
        // be loaded in unit tests, in which case it degrades to "unknown").
        assertFalse(banner.endsWith("libeasyss "))
    }

    @Test
    fun libeasyssVersion_neverThrowsWithoutNativeLibrary() {
        assertTrue(libeasyssVersion().isNotBlank())
    }

    @Test
    fun versionLogTag_isCapturedByLogStore() {
        // The banner is only visible in the viewer while its tag is part of the
        // logcat filter LogStore starts with.
        assertTrue(LogParser.APP_LOG_TAGS.contains(VERSION_LOG_TAG))
    }
}
