package com.backup.personal.network

import com.backup.personal.data.BackupItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class BackupApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class InitUploadResult(
        val sessionId: String,
        val isDuplicate: Boolean,
        val alreadyArchived: Boolean,
        val receivedBytes: Long,
        val chunkSizeRecommended: Int
    )

    data class HealthResult(
        val isHealthy: Boolean,
        val freeSpaceBytes: Long,
        val totalFiles: Long,
        val serviceVersion: String,
        val archiveName: String
    )

    data class ServerManifestItem(
        val contentHash: String,
        val originalName: String,
        val sizeBytes: Long
    )

    data class RestoreItem(
        val contentHash: String,
        val originalName: String,
        val sizeBytes: Long,
        val relativePath: String,
        val mediaType: String,
        val storageCategory: String
    )

    data class UpdateResult(
        val updateAvailable: Boolean,
        val latestVersion: String,
        val versionCode: Int,
        val apkSha256: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    fun checkHealth(host: String, port: Int): HealthResult? {
        return try {
            val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/health")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    HealthResult(
                        isHealthy = json.optBoolean("isHealthy", false),
                        freeSpaceBytes = json.optLong("freeSpaceBytes", 0L),
                        totalFiles = json.optLong("totalFiles", 0L),
                        serviceVersion = json.optString("serviceVersion", "1.0.0"),
                        archiveName = json.optString("archiveName", "Samsung Galaxy A05s Backup")
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun pair(host: String, port: Int, pairingCode: String, deviceId: String, deviceName: String): Pair<String?, String?> {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/auth/pair")
        val payload = JSONObject().apply {
            put("pairingCode", pairingCode)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                if (json.optBoolean("success", false)) {
                    val token = if (json.has("authToken")) json.getString("authToken") else null
                    val recoveryKey = if (json.has("recoveryKey")) json.getString("recoveryKey") else null
                    return Pair(token, recoveryKey)
                }
            }
        }
        return Pair(null, null)
    }

    fun recover(host: String, port: Int, recoveryKey: String, deviceId: String, deviceName: String): String? {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/auth/recover")
        val payload = JSONObject().apply {
            put("recoveryKey", recoveryKey)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                if (json.optBoolean("success", false)) {
                    return if (json.has("authToken")) json.getString("authToken") else null
                }
            }
        }
        return null
    }

    fun initUpload(host: String, port: Int, token: String, item: BackupItem): InitUploadResult {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/upload/init")
        val payload = JSONObject().apply {
            put("originalName", item.displayName)
            put("mediaType", item.mediaType)
            put("totalBytes", item.sizeBytes)
            put("contentHash", item.sha256Hash)
            put("clientCreatedAt", item.dateTaken.toString())
            put("isManualFile", item.isManualFile)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Init upload failed with HTTP ${response.code}: ${response.body?.string()}")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            return InitUploadResult(
                sessionId = json.getString("sessionId"),
                isDuplicate = json.optBoolean("isDuplicate", false),
                alreadyArchived = json.optBoolean("alreadyArchived", false),
                receivedBytes = json.optLong("receivedBytes", 0L),
                chunkSizeRecommended = json.optInt("chunkSizeRecommended", 2 * 1024 * 1024)
            )
        }
    }

    fun uploadChunk(
        host: String,
        port: Int,
        token: String,
        sessionId: String,
        offset: Long,
        chunk: ByteArray
    ): Long {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/upload/chunk/$sessionId")
        val body = chunk.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Offset", offset.toString())
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Upload chunk at offset $offset failed: HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            return json.optLong("totalReceived", offset + chunk.size)
        }
    }

    fun finalizeUpload(
        host: String,
        port: Int,
        token: String,
        sessionId: String,
        expectedSha256: String
    ): Boolean {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/upload/finalize/$sessionId")
        val payload = JSONObject().apply {
            put("expectedSha256", expectedSha256)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Finalize failed: HTTP ${response.code}: ${response.body?.string()}")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            return json.optBoolean("success", false)
        }
    }

    fun getReconcileManifest(host: String, port: Int, token: String): List<ServerManifestItem> {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/reconcile/manifest")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val list = mutableListOf<ServerManifestItem>()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val array = json.optJSONArray("items") ?: return emptyList()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ServerManifestItem(
                            contentHash = obj.getString("contentHash"),
                            originalName = obj.getString("originalName"),
                            sizeBytes = obj.getLong("sizeBytes")
                        )
                    )
                }
            }
        }
        return list
    }

    fun fetchRestoreList(host: String, port: Int, token: String): List<RestoreItem> {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/restore/list")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val list = mutableListOf<RestoreItem>()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val array = json.optJSONArray("files") ?: return emptyList()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        RestoreItem(
                            contentHash = obj.getString("contentHash"),
                            originalName = obj.getString("originalName"),
                            sizeBytes = obj.getLong("sizeBytes"),
                            relativePath = obj.getString("relativePath"),
                            mediaType = obj.optString("mediaType", "image/jpeg"),
                            storageCategory = obj.optString("storageCategory", "Photos")
                        )
                    )
                }
            }
        }
        return list
    }

    fun downloadRestoreFile(
        host: String,
        port: Int,
        token: String,
        contentHash: String,
        destFile: File
    ): Boolean {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/restore/file/$contentHash")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return true
        }
    }

    fun checkUpdates(host: String, port: Int): UpdateResult? {
        return try {
            val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/updates/check")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    UpdateResult(
                        updateAvailable = json.optBoolean("updateAvailable", false),
                        latestVersion = json.optString("latestVersion", "1.0.0"),
                        versionCode = json.optInt("versionCode", 1),
                        apkSha256 = json.optString("apkSha256", ""),
                        downloadUrl = json.optString("downloadUrl", ""),
                        releaseNotes = json.optString("releaseNotes", "")
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadUpdate(host: String, port: Int, destFile: File): Boolean {
        val url = NetworkTransportProvider.getTransport(host).buildUrl(host, port, "/api/v1/updates/download")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return true
        }
    }
}
