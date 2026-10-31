package com.easysstun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val UPDATE_REPO = "nange/EasyssTun"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
internal const val RELEASES_PAGE = "https://github.com/$UPDATE_REPO/releases/latest"

/** Subset of the GitHub release API response the updater needs. */
@Serializable
internal data class Release(
    @SerialName("tag_name") val tagName: String = "",
    /** Release notes; GitHub may omit it or send null. */
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
internal data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /** GitHub-provided digest, e.g. `sha256:...`; null on older releases. */
    val digest: String? = null,
)

/** Metadata parsed out of an APK asset name produced by `app/build.gradle`. */
internal data class ParsedApk(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val variant: String,
    val name: String,
)

/** A release APK that matches this device, plus where to fetch and how to verify it. */
internal data class ApkAsset(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

internal fun decodeRelease(body: String): Release? =
    runCatching { appJson.decodeFromString<Release>(body) }
        .getOrNull()
        ?.takeIf { it.tagName.isNotBlank() }

/**
 * Parses the release asset contract documented in AGENTS.md:
 * `EasyssTun_v{versionName}_{versionCode}_{abi}_{variant}_{yyyyMMdd}.apk`.
 */
internal object ApkNames {
    private val PATTERN =
        Regex("""^EasyssTun_v([^_]+)_(\d+)_([A-Za-z0-9-]+)_(release|debug)_\d{8}\.apk$""")

    fun parse(name: String): ParsedApk? {
        val match = PATTERN.matchEntire(name) ?: return null
        val versionCode = match.groupValues[2].toIntOrNull() ?: return null
        return ParsedApk(
            versionName = match.groupValues[1],
            versionCode = versionCode,
            abi = match.groupValues[3],
            variant = match.groupValues[4],
            name = name,
        )
    }
}

/**
 * Picks the release APK for this device: only `release` variants, only ABIs in
 * [supportedAbis] (a future universal APK is accepted as the last resort), and
 * the highest versionCode wins when several match.
 */
internal fun pickApk(release: Release, supportedAbis: List<String>): ApkAsset? {
    val candidates = release.assets.mapNotNull { asset ->
        val parsed = ApkNames.parse(asset.name) ?: return@mapNotNull null
        if (parsed.variant != "release") return@mapNotNull null
        val abiRank = abiRank(parsed.abi, supportedAbis) ?: return@mapNotNull null
        Triple(asset, parsed, abiRank)
    }
    val best = candidates
        .sortedWith(
            compareBy<Triple<ReleaseAsset, ParsedApk, Int>> { it.third }
                .thenByDescending { it.second.versionCode }
        )
        .firstOrNull() ?: return null

    val (asset, parsed, _) = best
    return ApkAsset(
        versionName = parsed.versionName,
        versionCode = parsed.versionCode,
        abi = parsed.abi,
        name = parsed.name,
        url = asset.downloadUrl,
        size = asset.size,
        sha256 = asset.digest?.let(::parseSha256Digest),
    )
}

private fun abiRank(abi: String, supportedAbis: List<String>): Int? {
    val index = supportedAbis.indexOf(abi)
    if (index >= 0) return index
    return if (abi == "all" || abi == "universal") supportedAbis.size else null
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

internal fun parseSha256Digest(digest: String): String? {
    val value = digest.trim().removePrefix("sha256:").lowercase()
    return value.takeIf { SHA256_HEX.matches(it) }
}
