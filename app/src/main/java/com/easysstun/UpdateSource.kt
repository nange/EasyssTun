package com.easysstun

import android.annotation.SuppressLint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "Updater"
private const val USER_AGENT = "EasyssTun-Android"
private const val LOOPBACK = "127.0.0.1"

private const val CHECK_CONNECT_TIMEOUT_MS = 8_000
private const val CHECK_READ_TIMEOUT_MS = 15_000
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

private const val MAX_RELEASE_BODY_BYTES = 1 shl 20
private const val BUFFER_SIZE = 64 * 1024

/** Network access for the updater; faked in unit tests. */
internal interface UpdateSource {
    suspend fun fetchLatestRelease(): Release

    /** Streams the APK to [dest] and reports progress as (downloaded, total). */
    suspend fun download(asset: ApkAsset, dest: File, onProgress: (Long, Long) -> Unit)
}

/**
 * GitHub-backed [UpdateSource].
 *
 * Mirrors easyss `selfupdate.Client`: when the VPN (libeasyss) is running, the
 * local HTTP proxy on `127.0.0.1:<socksPort+1000>` is tried first and a direct
 * connection is the fallback. The app's own traffic is excluded from the TUN,
 * so without this fallback a blocked network could never fetch an update.
 */
internal class GitHubUpdateSource(
    private val pref: Pref,
    private val proxyPortProvider: () -> Int? = {
        if (pref.isServiceEnabled) pref.getActiveProfile()?.statsPort() else null
    },
) : UpdateSource {

    override suspend fun fetchLatestRelease(): Release = withContext(Dispatchers.IO) {
        val body = getText(LATEST_RELEASE_API)
        decodeRelease(body)
            ?: throw UpdateException(UpdateError.MetadataMismatch, "unparsable release json")
    }

    override suspend fun download(
        asset: ApkAsset,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = dest.parentFile
            ?: throw UpdateException(UpdateError.Storage, "no parent directory for ${dest.name}")
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException(UpdateError.Storage, "cannot create ${dir.absolutePath}")
        }
        val usable = usableSpace(dir)
        if (asset.size > 0 && usable > 0 && usable < asset.size) {
            throw UpdateException(
                UpdateError.Storage,
                "need ${asset.size} bytes, only $usable available",
            )
        }

        var lastError: Throwable? = null
        for (port in proxyPorts()) {
            try {
                downloadOnce(asset, dest, port, onProgress)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                dest.delete()
                if (!isRetryable(e.error)) throw e
                lastError = e
                Log.w(TAG, "download via ${routeName(port)} failed: ${e.message}")
            } catch (e: IOException) {
                dest.delete()
                lastError = e
                Log.w(TAG, "download via ${routeName(port)} failed: ${e.message}")
            }
        }
        throw (lastError as? UpdateException)
            ?: UpdateException(UpdateError.Network, lastError?.message, lastError)
    }

    private suspend fun downloadOnce(
        asset: ApkAsset,
        dest: File,
        proxyPort: Int?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        val conn = openConnection(
            asset.url,
            proxyPort,
            DOWNLOAD_CONNECT_TIMEOUT_MS,
            DOWNLOAD_READ_TIMEOUT_MS,
        )
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw UpdateException(httpError(status), "http $status for ${asset.name}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReported = 0L
            onProgress(0L, total)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= BUFFER_SIZE) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            onProgress(downloaded, total)

            if (asset.size > 0 && downloaded != asset.size) {
                throw UpdateException(
                    UpdateError.ChecksumMismatch,
                    "downloaded $downloaded bytes, expected ${asset.size}",
                )
            }
            val actualSha256 = digest.digest().toHex()
            if (asset.sha256 != null && !actualSha256.equals(asset.sha256, ignoreCase = true)) {
                throw UpdateException(UpdateError.ChecksumMismatch, "sha256 mismatch")
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun getText(rawUrl: String): String {
        var lastError: Throwable? = null
        for (port in proxyPorts()) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(rawUrl, port, CHECK_CONNECT_TIMEOUT_MS, CHECK_READ_TIMEOUT_MS)
                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.use { readLimited(it) }
                }
                val error = httpError(status)
                if (status in 500..599) {
                    // Server-side hiccup: try the other route before giving up.
                    lastError = UpdateException(error, "http $status")
                    Log.w(TAG, "check via ${routeName(port)} failed: HTTP $status")
                    continue
                }
                throw UpdateException(error, "http $status from $rawUrl")
            } catch (e: UpdateException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "check via ${routeName(port)} failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        throw (lastError as? UpdateException)
            ?: UpdateException(UpdateError.Network, lastError?.message, lastError)
    }

    private fun openConnection(
        rawUrl: String,
        proxyPort: Int?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val url = URL(rawUrl)
        if (url.protocol != "https") {
            throw UpdateException(UpdateError.MetadataMismatch, "refusing non-https url: $rawUrl")
        }
        val connection = if (proxyPort != null && proxyPort > 0) {
            url.openConnection(
                Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, proxyPort)),
            ) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun proxyPorts(): List<Int?> {
        val port = runCatching { proxyPortProvider() }.getOrNull()
        return if (port != null && port > 0) listOf(port, null) else listOf(null)
    }
}

/**
 * Free space pre-check. StorageManager#getAllocatableBytes would consider
 * clearable cache of other apps, but the APK lands in our own cache dir, so
 * the plain value is an adequate early warning; the write itself is still
 * authoritative.
 */
@SuppressLint("UsableSpace")
private fun usableSpace(dir: File): Long = dir.usableSpace

private fun routeName(proxyPort: Int?): String =
    if (proxyPort != null) "local proxy $LOOPBACK:$proxyPort" else "direct"

/**
 * Transport failures and corrupt streams are worth retrying on the other route
 * (a tunnel-side 5xx is common when the VPN is up); HTTP 4xx and local errors
 * are not.
 */
private fun isRetryable(error: UpdateError): Boolean = when (error) {
    UpdateError.Network, UpdateError.ChecksumMismatch -> true
    is UpdateError.Http -> error.code >= 500
    else -> false
}

private fun httpError(status: Int): UpdateError =
    if (status == HttpURLConnection.HTTP_FORBIDDEN) UpdateError.RateLimited else UpdateError.Http(status)

private fun readLimited(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > MAX_RELEASE_BODY_BYTES) {
            throw UpdateException(UpdateError.MetadataMismatch, "release response too large")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX[byte.toInt() shr 4 and 0x0f])
        append(HEX[byte.toInt() and 0x0f])
    }
}

private val HEX = "0123456789abcdef".toCharArray()
