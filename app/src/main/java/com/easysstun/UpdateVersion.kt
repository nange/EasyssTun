package com.easysstun

/** 对齐 easyss `selfupdate.HasNewVersion`（`selfupdate/github.go`），故意偏离 semver：
 * 同前缀的预发布标识按数字尾比较，`rc9 < rc19`、`rc19.4 < rc19.10`；
 * semver 的字典序会把 `rc9` 排在 `rc19` 之前，与本项目 tag 不符。 */
internal fun isNewerVersion(current: String, latest: String): Boolean {
    if (current.isBlank()) return true
    val currentVersion = normalize(current, stripGitDescribe = true)
    val latestVersion = normalize(latest, stripGitDescribe = false)
    val parsedCurrent = parseVersion(currentVersion)
    val parsedLatest = parseVersion(latestVersion)
    if (parsedCurrent == null || parsedLatest == null) {
        // 本地构建/分叉的 tag 解析不了：退化为「字符串不同即视为新版」。
        return currentVersion != latestVersion
    }
    return compareVersions(parsedLatest, parsedCurrent) > 0
}

/** 匹配 `git describe` 在 tag 之后追加的 `-<n>-g<sha>` 后缀。 */
private val GIT_DESCRIBE_SUFFIX = Regex("-\\d+-g[0-9a-f]+$")

private val VERSION_PATTERN =
    Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

private data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?,
)

private fun normalize(raw: String, stripGitDescribe: Boolean): String {
    var text = raw.trim()
    if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
    if (stripGitDescribe) text = text.replace(GIT_DESCRIBE_SUFFIX, "")
    return text
}

private fun parseVersion(text: String): AppVersion? {
    val match = VERSION_PATTERN.matchEntire(text) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    val prerelease = match.groupValues[4].ifBlank { null }
    return AppVersion(major, minor, patch, prerelease)
}

private fun compareVersions(a: AppVersion, b: AppVersion): Int {
    if (a.major != b.major) return a.major.compareTo(b.major)
    if (a.minor != b.minor) return a.minor.compareTo(b.minor)
    if (a.patch != b.patch) return a.patch.compareTo(b.patch)
    val aPre = a.prerelease
    val bPre = b.prerelease
    return when {
        aPre == null && bPre == null -> 0
        // 正式版高于同 major.minor.patch 的任何预发布版。
        aPre == null -> 1
        bPre == null -> -1
        else -> comparePrerelease(aPre, bPre)
    }
}

/** `rc19.4` 解析为 {prefix="rc", major=19, minor=4}；无数字尾时返回 null。 */
private data class NumericPrerelease(val prefix: String, val major: Int, val minor: Int) : Comparable<NumericPrerelease> {
    override fun compareTo(other: NumericPrerelease): Int {
        if (major != other.major) return major.compareTo(other.major)
        return minor.compareTo(other.minor)
    }
}

private fun parseNumericPrerelease(prerelease: String): NumericPrerelease? {
    var index = 0
    while (index < prerelease.length && prerelease[index] !in '0'..'9') index++
    // 无字母前缀或无数字尾：交回 semver 规则判断。
    if (index == 0 || index == prerelease.length) return null

    val prefix = prerelease.substring(0, index)
    val parts = prerelease.substring(index).split(".", limit = 2)
    val major = parts[0].toIntOrNull() ?: return null
    val minor = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
    return NumericPrerelease(prefix, major, minor)
}

private fun comparePrerelease(a: String, b: String): Int {
    val numericA = parseNumericPrerelease(a)
    val numericB = parseNumericPrerelease(b)
    if (numericA != null && numericB != null && numericA.prefix == numericB.prefix) {
        return numericA.compareTo(numericB)
    }
    return compareSemverPrerelease(a, b)
}

/** semver 预发布优先级：纯数字标识低于字母数字标识。 */
private fun compareSemverPrerelease(a: String, b: String): Int {
    val aParts = a.split('.')
    val bParts = b.split('.')
    for (index in 0 until minOf(aParts.size, bParts.size)) {
        val aPart = aParts[index]
        val bPart = bParts[index]
        val aNumber = aPart.toLongOrNull()
        val bNumber = bPart.toLongOrNull()
        when {
            aNumber != null && bNumber != null -> {
                if (aNumber != bNumber) return aNumber.compareTo(bNumber)
            }
            aNumber != null -> return -1
            bNumber != null -> return 1
            else -> {
                val result = aPart.compareTo(bPart)
                if (result != 0) return result
            }
        }
    }
    return aParts.size.compareTo(bParts.size)
}
