package com.easysstun

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Profile data class, including serialization, defaults, and equality.
 */
class ProfileTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun createFullProfile() = Profile(
        id = "test-id-001",
        name = "My Server",
        server = "example.com",
        serverPort = "443",
        password = "secret123",
        encryption = "aes-256-gcm",
        proxyRule = "bypass_lan",
        outbound = "ipv4_only",
        logLevel = "debug",
        enableQuic = "true",
        ipv6Rule = "ipv6_only",
        serverNameIndication = "sni.example.com",
        customCa = "-----BEGIN CERTIFICATE-----\nMOCK",
        directFile = "direct_domains_list",
        proxyFile = "proxy_domains_list"
    )

    @Test
    fun serializeThenDeserialize_producesEqualProfile() {
        val original = createFullProfile()
        val encoded = json.encodeToString(Profile.serializer(), original)
        val decoded = json.decodeFromString(Profile.serializer(), encoded)

        assertEquals("Deserialized profile should equal original", original, decoded)
        assertEquals("ID should survive round-trip", original.id, decoded.id)
        assertEquals("Name should survive round-trip", original.name, decoded.name)
        assertEquals("Server should survive round-trip", original.server, decoded.server)
        assertEquals("ipv6Rule should survive round-trip", "ipv6_only", decoded.ipv6Rule)
    }

    @Test
    fun jsonWithLegacyDisableWarmUpKey_isIgnored() {
        // Profiles stored while the (since removed) disableWarmUp option existed
        // still carry the key. Dropping the option must not break decoding.
        val legacyJson = """
            {
                "id": "legacy",
                "name": "Legacy",
                "server": "legacy.example.com",
                "serverPort": "443",
                "password": "pw",
                "disableWarmUp": "true",
                "enableQuic": "true"
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), legacyJson)
        assertEquals("legacy", profile.id)
        assertEquals("legacy.example.com", profile.server)
        assertEquals("true", profile.enableQuic)
    }

    @Test
    fun parseBooleanFlag_onlyTrueIsTrue() {
        assertTrue(parseBooleanFlag("true"))
        assertTrue(parseBooleanFlag("TRUE"))
        assertTrue(parseBooleanFlag("True"))
        assertFalse(parseBooleanFlag("false"))
        assertFalse(parseBooleanFlag(""))
        assertFalse(parseBooleanFlag("yes"))
        assertFalse(parseBooleanFlag("1"))
    }

    @Test
    fun serializeThenDeserialize_minimalProfile_preservesDefaults() {
        // Only set required fields (all fields have defaults except constructor params without defaults)
        val minimal = Profile(
            id = "min-1",
            name = "Min Server",
            server = "min.example.com",
            serverPort = "8080",
            password = "pw",
            // All other fields use defaults
        )
        val encoded = json.encodeToString(Profile.serializer(), minimal)
        val decoded = json.decodeFromString(Profile.serializer(), encoded)

        assertEquals("ID should be preserved", "min-1", decoded.id)
        assertEquals("Default encryption should be chacha20-poly1305", "chacha20-poly1305", decoded.encryption)
        assertEquals("Default proxyRule should be auto", "auto", decoded.proxyRule)
        assertEquals("Default outbound should be native", "native", decoded.outbound)
        assertEquals("Default logLevel should be info", "info", decoded.logLevel)
        assertEquals("Default enableQuic should be false", "false", decoded.enableQuic)
        assertEquals("Default ipv6Rule should be auto", "auto", decoded.ipv6Rule)
        assertEquals("Default serverNameIndication should be empty", "", decoded.serverNameIndication)
        assertEquals("Default customCa should be empty", "", decoded.customCa)
        assertEquals("Default directFile should be empty", "", decoded.directFile)
        assertEquals("Default proxyFile should be empty", "", decoded.proxyFile)
        assertEquals("Default socksPort should be 2080", "2080", decoded.socksPort)
    }

    @Test
    fun copy_createsEqualProfile() {
        val original = createFullProfile()
        val copied = original.copy()
        assertEquals("Copy should equal original", original, copied)
        assertNotSame("Copy should be a different instance", original, copied)
    }

    @Test
    fun copy_withChangedFields_createsDifferentProfile() {
        val original = createFullProfile()
        val modified = original.copy(name = "New Name", serverPort = "9090")

        assertEquals("ID should be unchanged", original.id, modified.id)
        assertEquals("Name should be updated", "New Name", modified.name)
        assertEquals("Server port should be updated", "9090", modified.serverPort)
        assertNotEquals("Modified profile should not equal original", original, modified)
    }

    @Test
    fun profilesWithSameId_areEqual() {
        val p1 = createFullProfile()
        val p2 = createFullProfile()
        assertEquals("Identical profiles should be equal", p1, p2)
        assertEquals("Hash codes should match for equal objects", p1.hashCode(), p2.hashCode())
    }

    @Test
    fun profilesWithDifferentId_areNotEqual() {
        val p1 = createFullProfile()
        val p2 = createFullProfile().copy(id = "different-id")
        assertNotEquals("Different IDs should make profiles not equal", p1, p2)
    }

    @Test
    fun jsonWithUnknownKeys_isIgnored() {
        val jsonWithExtra = """
            {
                "id": "extra-keys-test",
                "name": "Test",
                "server": "s.example.com",
                "serverPort": "443",
                "password": "pw",
                "unknownField": "should be ignored",
                "anotherUnknown": 123
            }
        """.trimIndent()

        val profile = json.decodeFromString(Profile.serializer(), jsonWithExtra)
        assertEquals("extra-keys-test", profile.id)
        assertEquals("Test", profile.name)
        assertEquals("s.example.com", profile.server)
    }

    @Test
    fun allFieldsSerialized_producesValidJson() {
        val profile = createFullProfile()
        val encoded = json.encodeToString(Profile.serializer(), profile)

        assertTrue("JSON should contain id", encoded.contains("\"id\":\"test-id-001\""))
        assertTrue("JSON should contain encryption", encoded.contains("\"encryption\":\"aes-256-gcm\""))
        assertTrue("JSON should contain customCa", encoded.contains("\"customCa\""))
        assertFalse("JSON must no longer contain the removed disableWarmUp key", encoded.contains("disableWarmUp"))
        assertTrue("JSON should contain directFile", encoded.contains("\"directFile\":\"direct_domains_list\""))
        assertTrue("JSON should contain proxyFile", encoded.contains("\"proxyFile\":\"proxy_domains_list\""))
    }

    // ── Stats endpoint (SOCKS port + 1000) ──────────────────────────────

    @Test
    fun statsPort_withDefaultSocksPort_returns3080() {
        val profile = createFullProfile()
        assertEquals("Default socks port 2080 should map to stats port 3080", 3080, profile.statsPort())
    }

    @Test
    fun statsUrl_withDefaultSocksPort_usesPort3080() {
        val profile = createFullProfile()
        assertEquals("http://127.0.0.1:3080/stats", profile.statsUrl())
    }

    @Test
    fun statsPort_withCustomSocksPort_followsPortPlus1000() {
        val profile = createFullProfile().copy(socksPort = "1080")
        assertEquals("Socks port 1080 should map to stats port 2080", 2080, profile.statsPort())
        assertEquals("http://127.0.0.1:2080/stats", profile.statsUrl())
    }

    @Test
    fun statsPort_withBlankOrInvalidSocksPort_fallsBackToDefault() {
        assertEquals("Blank socks port should fall back to 3080", 3080, createFullProfile().copy(socksPort = "").statsPort())
        assertEquals("Non-numeric socks port should fall back to 3080", 3080, createFullProfile().copy(socksPort = "abc").statsPort())
        assertEquals("Blank socks port should fall back to default URL", "http://127.0.0.1:3080/stats", createFullProfile().copy(socksPort = "").statsUrl())
    }

    @Test
    fun defaultStatsUrl_pointsToDefaultStatsPort() {
        assertEquals("http://127.0.0.1:3080/stats", Profile.defaultStatsUrl())
    }

    // ── Timeout (native `-t/--timeout` knob) ────────────────────────────

    @Test
    fun defaultTimeout_isNativeDefault() {
        assertEquals("30", Profile.DEFAULT_TIMEOUT)
        assertEquals("Default timeout should be 30 seconds", Profile.DEFAULT_TIMEOUT, minimalProfile().timeout)
    }

    @Test
    fun parseTimeoutSeconds_acceptsWholeNumbersWithinRange() {
        assertEquals(Profile.MIN_TIMEOUT_SECONDS, parseTimeoutSeconds("15"))
        assertEquals(Profile.MAX_TIMEOUT_SECONDS, parseTimeoutSeconds("60"))
        assertEquals(45, parseTimeoutSeconds("45"))
        assertEquals("Surrounding whitespace must be ignored", 45, parseTimeoutSeconds("  45\t"))
    }

    @Test
    fun parseTimeoutSeconds_blankMeansNativeDefault() {
        assertEquals("Blank timeout means \"let the native default apply\"", 0, parseTimeoutSeconds(""))
        assertEquals(0, parseTimeoutSeconds("   "))
    }

    @Test
    fun parseTimeoutSeconds_rejectsOutOfRangeAndNonNumericValues() {
        assertNull("Below the native range", parseTimeoutSeconds("14"))
        assertNull("Above the native range", parseTimeoutSeconds("61"))
        assertNull(parseTimeoutSeconds("0"))
        assertNull(parseTimeoutSeconds("-30"))
        assertNull(parseTimeoutSeconds("abc"))
        assertNull(parseTimeoutSeconds("30s"))
        assertNull(parseTimeoutSeconds("30.5"))
    }

    @Test
    fun timeoutSeconds_mapsFieldOntoNativeValue() {
        assertEquals(0, minimalProfile().copy(timeout = "").timeoutSeconds())
        assertEquals(45, minimalProfile().copy(timeout = " 45 ").timeoutSeconds())
        assertEquals("A hand-edited out-of-range value falls back to the native default", 0, minimalProfile().copy(timeout = "999").timeoutSeconds())
    }

    @Test
    fun jsonWithoutTimeoutKey_fallsBackToDefaultTimeout() {
        // Profiles stored before the timeout option existed carry no key; they must
        // decode to the native default instead of failing or serializing to null.
        val legacyJson = """
            {
                "id": "legacy-timeout",
                "name": "Legacy",
                "server": "legacy.example.com",
                "serverPort": "443",
                "password": "pw"
            }
        """.trimIndent()

        assertEquals("30", json.decodeFromString(Profile.serializer(), legacyJson).timeout)
    }

    @Test
    fun timeout_survivesSerializationRoundTrip() {
        val original = createFullProfile().copy(timeout = "45")
        val decoded = json.decodeFromString(Profile.serializer(), json.encodeToString(Profile.serializer(), original))

        assertEquals("45", decoded.timeout)
        assertEquals(original, decoded)
    }

    private fun minimalProfile() = Profile(
        id = "min-timeout",
        name = "Min Server",
        server = "min.example.com",
        serverPort = "443",
        password = "pw"
    )
}
