package com.easysstun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the config-file writer: content comparison (unchanged files are not
 * rewritten), the profile-file paths handed to the native config, and the
 * failure result the service surfaces to the user.
 */
class ProfileConfigWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writer(): ProfileConfigWriter = ProfileConfigWriter(tmp.root)

    private fun profile(
        customCa: String = "",
        directFile: String = "",
        proxyFile: String = "",
    ) = Profile(
        id = "writer-test",
        name = "Writer Test",
        server = "example.com",
        serverPort = 443,
        password = "secret",
        customCa = customCa,
        directFile = directFile,
        proxyFile = proxyFile,
    )

    @Test
    fun writeProfileFiles_writesEachConfiguredFileAndReturnsItsPath() {
        val result = writer().writeProfileFiles(
            profile(
                customCa = "-----BEGIN CERTIFICATE-----\nMOCK",
                directFile = "direct.example.com",
                proxyFile = "proxy.example.com",
            )
        )

        val paths = result.getOrThrow()
        val ca = File(tmp.root, ProfileConfigWriter.CUSTOM_CA_FILE)
        assertEquals(ca.absolutePath, paths.customCa)
        assertEquals("-----BEGIN CERTIFICATE-----\nMOCK", ca.readText())
        assertEquals("direct.example.com", File(tmp.root, ProfileConfigWriter.DIRECT_FILE).readText())
        assertEquals("proxy.example.com", File(tmp.root, ProfileConfigWriter.PROXY_FILE).readText())
    }

    @Test
    fun writeProfileFiles_blankFieldsAreNotWrittenAndHaveNoPath() {
        val paths = writer().writeProfileFiles(profile()).getOrThrow()

        assertNull(paths.customCa)
        assertNull(paths.directFile)
        assertNull(paths.proxyFile)
        assertFalse(File(tmp.root, ProfileConfigWriter.CUSTOM_CA_FILE).exists())
        assertFalse(File(tmp.root, ProfileConfigWriter.DIRECT_FILE).exists())
        assertFalse(File(tmp.root, ProfileConfigWriter.PROXY_FILE).exists())
    }

    @Test
    fun writeProfileFiles_unchangedContentIsNotRewritten() {
        val writer = writer()
        val profile = profile(customCa = "cert")
        assertTrue(writer.writeProfileFiles(profile).isSuccess)

        val file = File(tmp.root, ProfileConfigWriter.CUSTOM_CA_FILE)
        val stamp = 1_600_000_000_000L
        assertTrue(file.setLastModified(stamp))

        // Same content: the file must be left untouched (its mtime proves it).
        assertTrue(writer.writeProfileFiles(profile).isSuccess)
        assertEquals("an unchanged file must not be rewritten", stamp, file.lastModified())

        // Different content: the file is rewritten.
        assertTrue(writer.writeProfileFiles(profile.copy(customCa = "new cert")).isSuccess)
        assertEquals("new cert", file.readText())
    }

    @Test
    fun writeProfileFiles_ioFailureIsReturnedAsAFailedResult() {
        // A regular file used as the cache dir makes every write fail.
        val blocker = File(tmp.root, "blocker").apply { writeText("not a directory") }
        val writer = ProfileConfigWriter(File(blocker, "cache"))

        val result = writer.writeProfileFiles(profile(customCa = "cert"))

        assertTrue("a failed write must not be swallowed", result.isFailure)
    }

    @Test
    fun writeTproxyConfig_writesSocksPortAndTunnelTimeouts() {
        val result = writer().writeTproxyConfig(4080)

        val file = result.getOrThrow()
        assertEquals(ProfileConfigWriter.TPROXY_FILE, file.name)
        val content = file.readText()
        assertTrue("SOCKS port must be rendered", content.contains("port: 4080"))
        assertTrue("loopback only", content.contains("address: '127.0.0.1'"))
        assertTrue("UDP must stay enabled", content.contains("udp: 'udp'"))
        assertTrue("TCP idle timeout", content.contains("tcp-read-write-timeout: 480000"))
        assertTrue("UDP idle timeout", content.contains("udp-read-write-timeout: 15000"))
    }

    @Test
    fun writeTproxyConfig_unchangedPortIsNotRewritten() {
        val writer = writer()
        val file = writer.writeTproxyConfig(4080).getOrThrow()
        val stamp = 1_600_000_000_000L
        assertTrue(file.setLastModified(stamp))

        assertTrue(writer.writeTproxyConfig(4080).isSuccess)
        assertEquals("the same port must not rewrite the file", stamp, file.lastModified())

        assertTrue(writer.writeTproxyConfig(1080).isSuccess)
        assertTrue(file.readText().contains("port: 1080"))
    }

    @Test
    fun writeTproxyConfig_ioFailureIsReturnedAsAFailedResult() {
        val blocker = File(tmp.root, "blocker").apply { writeText("not a directory") }
        val writer = ProfileConfigWriter(File(blocker, "cache"))

        assertTrue(writer.writeTproxyConfig(4080).isFailure)
    }
}
