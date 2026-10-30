package com.easysstun

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest

private const val TAG = "Updater"
private const val UPDATE_DIR = "updates"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Result of validating a downloaded APK before handing it to the system installer. */
internal sealed interface InstallCheck {
    data object Ok : InstallCheck
    data object InvalidApk : InstallCheck
    data object WrongPackage : InstallCheck
    data object VersionMismatch : InstallCheck
    data object SignerMismatch : InstallCheck
}

/**
 * Installs downloaded APKs through the system package installer.
 *
 * Signature comparison is the last line of defence: HTTPS and the GitHub API
 * authenticate the download, but only the signing certificate proves the APK
 * can update this particular installation.
 */
internal object UpdateInstaller {

    fun updateDir(context: Context): File = File(context.cacheDir, UPDATE_DIR)

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent {
        val packageUri = "package:${context.packageName}".toUri()
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
        if (intent.resolveActivity(context.packageManager) == null) {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
        return intent
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun verify(
        context: Context,
        apk: File,
        expectedVersionCode: Int,
        // Injectable for unit tests: the PackageInfoFlags overload of
        // getPackageArchiveInfo() parses the APK directly, bypassing
        // Robolectric's shadow, so tests supply the archive metadata.
        archiveInfo: (String) -> PackageInfo? = { path ->
            context.packageManager.getPackageArchiveInfo(path, signingFlags())
        },
    ): InstallCheck {
        if (!apk.isFile || apk.length() <= 0L) return InstallCheck.InvalidApk

        val packageManager = context.packageManager
        val archive = runCatching { archiveInfo(apk.absolutePath) }.getOrNull()
            ?: return InstallCheck.InvalidApk

        if (archive.packageName != BuildConfig.APPLICATION_ID) return InstallCheck.WrongPackage
        if (archive.longVersionCode != expectedVersionCode.toLong()) return InstallCheck.VersionMismatch

        val archiveSigners = signerDigests(archive)
        val installed = runCatching {
            packageManager.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull()
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) {
            Log.w(TAG, "cannot read signing certificates (archive=${archiveSigners.size}, installed=${installedSigners.size})")
            return InstallCheck.InvalidApk
        }
        return if (archiveSigners == installedSigners) InstallCheck.Ok else InstallCheck.SignerMismatch
    }

    /** Deletes previously downloaded APKs and interrupted `.part` files. */
    fun cleanup(context: Context) {
        val dir = updateDir(context)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                if (!file.delete()) Log.w(TAG, "cannot delete stale update file ${file.name}")
            }
        }
    }

    private fun signingFlags(): PackageManager.PackageInfoFlags =
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    private fun signerDigests(info: PackageInfo?): Set<String> {
        val signingInfo = info?.signingInfo ?: return emptySet()
        val signatures = runCatching { signingInfo.apkContentsSigners }.getOrNull() ?: return emptySet()
        return signatures.mapNotNullTo(mutableSetOf()) { signature ->
            runCatching { sha256(signature.toByteArray()) }.getOrNull()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for ((index, byte) in digest.withIndex()) {
            val value = byte.toInt() and 0xff
            hex[index * 2] = digits[value shr 4]
            hex[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(hex)
    }
}
