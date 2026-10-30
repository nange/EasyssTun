package com.easysstun

/**
 * Version comparison used by the in-app updater.
 *
 * This is a Kotlin port of easyss `selfupdate.HasNewVersion` (see
 * `selfupdate/github.go`), including its deliberate divergence from the semver
 * spec: prerelease identifiers with the same leading word are compared by their
 * numeric tail, so `rc9 < rc19` and `rc19.4 < rc19.10`. The spec's lexical rule
 * would order `rc9` above `rc19`, which is wrong for this project's tags
 * (`v3.0.0-rc19.4`).
 */
internal fun isNewerVersion(current: String, latest: String): Boolean {
    if (current.isBlank()) return true
    val currentVersion = normalize(current, stripGitDescribe = true)
    val latestVersion = normalize(latest, stripGitDescribe = false)
    val parsedCurrent = parseVersion(currentVersion)
    val parsedLatest = parseVersion(latestVersion)
    if (parsedCurrent == null || parsedLatest == null) {
        // Unparseable tags (local builds, forks): fall back to "differs".
        return currentVersion != latestVersion
    }
    return compareVersions(parsedLatest, parsedCurrent) > 0
}

/** Matches the `-<n>-g<sha>` suffix `git describe` appends past the last tag. */
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
        // A release outranks any prerelease of the same major.minor.patch.
        aPre == null -> 1
        bPre == null -> -1
        else -> comparePrerelease(aPre, bPre)
    }
}

/** `rc19.4` -> {prefix = "rc", major = 19, minor = 4}, or null without a numeric tail. */
private data class NumericPrerelease(val prefix: String, val major: Int, val minor: Int) : Comparable<NumericPrerelease> {
    override fun compareTo(other: NumericPrerelease): Int {
        if (major != other.major) return major.compareTo(other.major)
        return minor.compareTo(other.minor)
    }
}

private fun parseNumericPrerelease(prerelease: String): NumericPrerelease? {
    var index = 0
    while (index < prerelease.length && prerelease[index] !in '0'..'9') index++
    // No alphabetic prefix or no numeric tail: let the semver rules decide.
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

/** Standard semver prerelease precedence: numeric identifiers < alphanumeric. */
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
