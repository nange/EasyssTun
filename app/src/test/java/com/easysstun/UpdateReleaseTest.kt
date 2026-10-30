package com.easysstun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {

    private val releaseJson = """
        {
          "url": "https://api.github.com/repos/nange/EasyssTun/releases/1",
          "tag_name": "v3.0.0-rc19.4",
          "name": "v3.0.0-rc19.4",
          "body": "release notes",
          "prerelease": false,
          "draft": false,
          "html_url": "https://github.com/nange/EasyssTun/releases/tag/v3.0.0-rc19.4",
          "assets": [
            {
              "name": "EasyssTun_v3.0.0-rc19.4_169_arm64-v8a_release_20261025.apk",
              "size": 20420267,
              "digest": "sha256:EA93E91F0035C6F14F345BB61A9F923C521835540BA066B864493BFF3135142C",
              "browser_download_url": "https://github.com/nange/EasyssTun/releases/download/v3.0.0-rc19.4/EasyssTun_v3.0.0-rc19.4_169_arm64-v8a_release_20261025.apk"
            }
          ]
        }
    """.trimIndent()

    private fun asset(
        versionName: String = "3.0.0-rc20",
        versionCode: Int = 170,
        abi: String = "arm64-v8a",
        variant: String = "release",
        size: Long = 100,
        digest: String? = "sha256:" + "ab".repeat(32),
    ): ReleaseAsset {
        val name = "EasyssTun_v${versionName}_${versionCode}_${abi}_${variant}_20261030.apk"
        return ReleaseAsset(
            name = name,
            downloadUrl = "https://github.com/nange/EasyssTun/releases/download/v$versionName/$name",
            size = size,
            digest = digest,
        )
    }

    private fun release(vararg assets: ReleaseAsset, tag: String = "v3.0.0-rc20") = Release(
        tagName = tag,
        body = "notes",
        htmlUrl = "https://github.com/nange/EasyssTun/releases/tag/$tag",
        assets = assets.toList(),
    )

    @Test
    fun decodesReleaseResponse() {
        val release = decodeRelease(releaseJson)
        assertNotNull(release)
        assertEquals("v3.0.0-rc19.4", release!!.tagName)
        assertEquals("release notes", release.body)
        assertEquals(
            "https://github.com/nange/EasyssTun/releases/tag/v3.0.0-rc19.4",
            release.htmlUrl,
        )
        assertEquals(1, release.assets.size)
        assertEquals(20420267L, release.assets[0].size)
        assertEquals(
            "ea93e91f0035c6f14f345bb61a9f923c521835540ba066b864493bff3135142c",
            parseSha256Digest(release.assets[0].digest!!),
        )
    }

    @Test
    fun malformedJsonIsRejected() {
        assertNull(decodeRelease("not json"))
        assertNull(decodeRelease("{}"))
    }

    @Test
    fun nullReleaseNotesAreTolerated() {
        val release = decodeRelease("""{"tag_name":"v3.0.0","body":null,"assets":[]}""")
        assertNotNull(release)
        assertNull(release!!.body)
    }

    @Test
    fun parsesAssetNameContract() {
        val parsed = ApkNames.parse(
            "EasyssTun_v3.0.0-rc19.4_169_arm64-v8a_release_20261025.apk",
        )
        assertNotNull(parsed)
        assertEquals("3.0.0-rc19.4", parsed!!.versionName)
        assertEquals(169, parsed.versionCode)
        assertEquals("arm64-v8a", parsed.abi)
        assertEquals("release", parsed.variant)

        assertNull(ApkNames.parse("EasyssTun_v3.0.0_1_arm64-v8a_release.apk"))
        assertNull(ApkNames.parse("some-other-app.apk"))
        assertNull(ApkNames.parse("EasyssTun_v3.0.0_1_arm64-v8a_release_20261030.apk.sha256"))
    }

    @Test
    fun picksReleaseApkForSupportedAbi() {
        val selected = pickApk(
            release(
                asset(abi = "x86_64", versionCode = 300),
                asset(abi = "arm64-v8a", versionCode = 170),
            ),
            supportedAbis = listOf("arm64-v8a"),
        )
        assertNotNull(selected)
        assertEquals("arm64-v8a", selected!!.abi)
        assertEquals(170, selected.versionCode)
    }

    @Test
    fun ignoresDebugVariants() {
        assertNull(pickApk(release(asset(variant = "debug")), listOf("arm64-v8a")))
    }

    @Test
    fun picksHighestVersionCode() {
        val selected = pickApk(
            release(asset(versionCode = 170), asset(versionCode = 172)),
            listOf("arm64-v8a"),
        )
        assertEquals(172, selected!!.versionCode)
    }

    @Test
    fun prefersDeviceAbiOverUniversalApk() {
        val selected = pickApk(
            release(
                asset(abi = "all", versionCode = 200),
                asset(abi = "arm64-v8a", versionCode = 170),
            ),
            listOf("arm64-v8a"),
        )
        assertEquals("arm64-v8a", selected!!.abi)
    }

    @Test
    fun acceptsUniversalApkWhenNoAbiMatches() {
        val selected = pickApk(
            release(asset(abi = "all", versionCode = 200)),
            listOf("arm64-v8a"),
        )
        assertEquals("all", selected!!.abi)
    }

    @Test
    fun mapsDigestAndSizeIntoApkAsset() {
        val selected = pickApk(
            release(asset(size = 20420267L, digest = "sha256:" + "AB".repeat(32))),
            listOf("arm64-v8a"),
        )
        assertEquals(20420267L, selected!!.size)
        assertEquals("ab".repeat(32), selected.sha256)
    }

    @Test
    fun missingOrForeignDigestBecomesNull() {
        assertNull(pickApk(release(asset(digest = null)), listOf("arm64-v8a"))!!.sha256)
        assertNull(pickApk(release(asset(digest = "md5:abcd")), listOf("arm64-v8a"))!!.sha256)
    }

    @Test
    fun invalidDigestIsRejected() {
        assertNull(parseSha256Digest("sha256:nothex"))
        assertNull(parseSha256Digest("sha256:" + "ab".repeat(31)))
        assertTrue(parseSha256Digest("sha256:" + "ab".repeat(32))!!.isNotEmpty())
    }

    @Test
    fun noCompatibleAssetReturnsNull() {
        assertNull(pickApk(release(asset(abi = "x86_64")), listOf("arm64-v8a")))
    }
}
