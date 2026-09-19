package com.backup.personal.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.backup.personal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val expectedSha256: String?
)

class AppUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val GITHUB_OWNER = "Pixel-999-git"
        const val GITHUB_REPO = "personal-backup-system"
        const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    suspend fun checkForUpdates(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "PersonalBackupAndroidApp")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateInfo(
                        hasUpdate = false,
                        latestVersion = BuildConfig.VERSION_NAME,
                        currentVersion = BuildConfig.VERSION_NAME,
                        releaseNotes = "No updates available or network unreachable",
                        apkDownloadUrl = null,
                        expectedSha256 = null
                    )
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val tagName = json.optString("tag_name", "").removePrefix("v")
                val releaseNotes = json.optString("body", "Bug fixes and improvements")
                val assets = json.optJSONArray("assets")

                var downloadUrl: String? = null
                var expectedSha256: String? = null

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                        }
                    }
                }

                val isNewer = isVersionNewer(tagName, BuildConfig.VERSION_NAME)
                return@withContext UpdateInfo(
                    hasUpdate = isNewer && downloadUrl != null,
                    latestVersion = if (tagName.isNotEmpty()) tagName else BuildConfig.VERSION_NAME,
                    currentVersion = BuildConfig.VERSION_NAME,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = downloadUrl,
                    expectedSha256 = expectedSha256
                )
            }
        } catch (e: Exception) {
            return@withContext UpdateInfo(
                hasUpdate = false,
                latestVersion = BuildConfig.VERSION_NAME,
                currentVersion = BuildConfig.VERSION_NAME,
                releaseNotes = "Check failed: ${e.message}",
                apkDownloadUrl = null,
                expectedSha256 = null
            )
        }
    }

    suspend fun downloadAndInstallApk(
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "PersonalBackupAndroidApp")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed with HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty download response body"))
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                            }
                        }
                        output.flush()
                    }
                }
            }

            return@withContext Result.success(apkFile)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    fun promptInstall(apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rVal = rParts.getOrElse(i) { 0 }
            val lVal = lParts.getOrElse(i) { 0 }
            if (rVal > lVal) return true
            if (rVal < lVal) return false
        }
        return false
    }
}
