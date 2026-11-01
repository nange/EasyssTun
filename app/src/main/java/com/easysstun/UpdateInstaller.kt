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

private const val TAG = LogTags.UPDATER
private const val UPDATE_DIR = "updates"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** 下载 APK 交给系统安装器前的校验结果。 */
internal sealed interface InstallCheck {
    data object Ok : InstallCheck
    data object InvalidApk : InstallCheck
    data object WrongPackage : InstallCheck
    data object VersionMismatch : InstallCheck
    data object SignerMismatch : InstallCheck
}

/** 经系统包安装器安装已下载的 APK；签名比对是最后一道防线：HTTPS/API 只证明来源，
 * 只有签名证书能证明该 APK 可覆盖安装到本机。 */
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
        // 可注入以便单测：PackageInfoFlags 版 getPackageArchiveInfo() 直接解析 APK，
        // 绕过 Robolectric 的 shadow，测试只能自行提供 archive 元数据。
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

    /** 清理历史下载的 APK 与中断的 `.part` 文件。 */
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
