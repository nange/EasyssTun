package com.easysstun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-parity tests for easyss `selfupdate.HasNewVersion`: rc numbers must be
 * compared numerically, because the semver spec orders `rc9` above `rc19`.
 */
class UpdateVersionTest {

    @Test
    fun identicalVersionsAreNotNewer() {
        assertFalse(isNewerVersion("3.0.0-rc19.4", "v3.0.0-rc19.4"))
        assertFalse(isNewerVersion("v3.0.0", "3.0.0"))
    }

    @Test
    fun patchAndMinorAndMajorBumpsAreNewer() {
        assertTrue(isNewerVersion("3.0.0", "v3.0.1"))
        assertTrue(isNewerVersion("3.0.0", "v3.1.0"))
        assertTrue(isNewerVersion("3.0.0", "v4.0.0"))
        assertFalse(isNewerVersion("3.1.0", "v3.0.9"))
        assertFalse(isNewerVersion("3.0.1", "v3.0.0"))
    }

    @Test
    fun releaseCandidateNumbersAreComparedNumerically() {
        assertTrue(isNewerVersion("3.0.0-rc9", "v3.0.0-rc19"))
        assertFalse(isNewerVersion("3.0.0-rc19", "v3.0.0-rc9"))
        assertTrue(isNewerVersion("3.0.0-rc19.4", "v3.0.0-rc19.10"))
        assertFalse(isNewerVersion("3.0.0-rc19.10", "v3.0.0-rc19.4"))
        assertTrue(isNewerVersion("3.0.0-rc19", "v3.0.0-rc19.1"))
        assertFalse(isNewerVersion("3.0.0-rc19.1", "v3.0.0-rc19"))
    }

    @Test
    fun stableReleaseOutranksPrerelease() {
        assertTrue(isNewerVersion("3.0.0-rc19", "v3.0.0"))
        assertFalse(isNewerVersion("3.0.0", "v3.0.0-rc19"))
    }

    @Test
    fun gitDescribeSuffixIsIgnored() {
        assertFalse(isNewerVersion("v3.0.0-rc19.4-3-gabc1234", "v3.0.0-rc19.4"))
        assertTrue(isNewerVersion("v3.0.0-rc19.4-3-gabc1234", "v3.0.0-rc19.5"))
    }

    @Test
    fun numericPrereleaseIdentifiersFollowSemver() {
        assertTrue(isNewerVersion("3.0.0-1", "v3.0.0-2"))
        assertFalse(isNewerVersion("3.0.0-2", "v3.0.0-1"))
        assertTrue(isNewerVersion("3.0.0-rc1", "v3.0.0-rc1.1"))
    }

    @Test
    fun unparseableTagsFallBackToInequality() {
        assertFalse(isNewerVersion("nightly-77f425b", "nightly-77f425b"))
        assertTrue(isNewerVersion("nightly-77f425b", "nightly-e2c9aa3"))
    }

    @Test
    fun blankCurrentVersionIsAlwaysOutdated() {
        assertTrue(isNewerVersion("", "v3.0.0"))
    }

    @Test
    fun buildMetadataDoesNotAffectOrdering() {
        assertFalse(isNewerVersion("3.0.0-rc19.4+1", "v3.0.0-rc19.4+2"))
        assertTrue(isNewerVersion("3.0.0-rc19.4+1", "v3.0.0-rc19.5+1"))
    }
}
