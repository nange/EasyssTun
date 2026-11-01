package com.easysstun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val UPDATE_REPO = "nange/EasyssTun"
internal const val LATEST_RELEASE_API = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
internal const val RELEASES_PAGE = "https://github.com/$UPDATE_REPO/releases/latest"

/** 更新器用到的 GitHub release API 字段子集。 */
@Serializable
internal data class Release(
    @SerialName("tag_name") val tagName: String = "",
    /** 发布说明；GitHub 可能省略或返回 null。 */
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
internal data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /** GitHub 提供的摘要，形如 `sha256:...`；旧 release 为 null。 */
    val digest: String? = null,
)

/** 从 `app/build.gradle` 生成的 APK 资产名中解析出的元数据。 */
internal data class ParsedApk(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val variant: String,
    val name: String,
)

/** 匹配本机的 release APK，含下载地址与校验信息。 */
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

/** 解析资产命名契约（见 AGENTS.md）：
 * `EasyssTun_v{versionName}_{versionCode}_{abi}_{variant}_{yyyyMMdd}.apk`。 */
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

/** 选包约束：只要 `release` 变体、ABI 必须在 [supportedAbis] 内（`all`/`universal` 作为最后兜底），
 * 多个命中时取 versionCode 最大者。 */
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
