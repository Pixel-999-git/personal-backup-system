package com.backup.personal.restore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.backup.personal.data.BackupDatabaseHelper
import com.backup.personal.data.BackupItem
import com.backup.personal.data.BackupState
import com.backup.personal.data.PreferencesManager
import com.backup.personal.network.BackupApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

class RestoreEngine(private val context: Context) {
    private val dbHelper = BackupDatabaseHelper.getInstance(context)
    private val prefs = PreferencesManager(context)
    private val apiClient = BackupApiClient()
    private val tempDir = File(context.cacheDir, "restore_temp").apply { mkdirs() }

    suspend fun restoreAllFromArchive(
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext Pair(0, 0)
        val token = prefs.authToken ?: return@withContext Pair(0, 0)
        val host = prefs.serverHost
        val port = prefs.serverPort

        val files = apiClient.fetchRestoreList(host, port, token)
        var restoredCount = 0
        var skippedCount = 0

        val existingItems = dbHelper.getAllItems().associateBy { it.sha256Hash.lowercase() }

        for ((index, item) in files.withIndex()) {
            val normalizedHash = item.contentHash.lowercase()

            // 1. If already locally recorded as VERIFIED, skip re-downloading
            if (existingItems.containsKey(normalizedHash) && existingItems[normalizedHash]?.state == BackupState.VERIFIED) {
                skippedCount++
                continue
            }

            onProgress?.invoke(index + 1, files.size, item.originalName)

            // 2. Download from Windows to temporary cache file
            val tempFile = File(tempDir, "${normalizedHash}.part")
            try {
                val downloaded = apiClient.downloadRestoreFile(host, port, token, item.contentHash, tempFile)
                if (!downloaded || !tempFile.exists()) continue

                // 3. Verify SHA-256 integrity of restored file
                val actualHash = computeFileSha256(tempFile)
                if (!actualHash.equals(normalizedHash, ignoreCase = true)) {
                    tempFile.delete()
                    continue // Hash mismatch; discard corrupted transfer
                }

                // 4. Insert into Android MediaStore using Scoped Storage
                val restoredUri = insertIntoMediaStore(tempFile, item.originalName, item.mediaType, item.storageCategory)

                // 5. Commit record into phone database as VERIFIED to prevent re-upload loops!
                val dbItem = BackupItem(
                    id = UUID.randomUUID().toString(),
                    uriString = restoredUri?.toString() ?: tempFile.absolutePath,
                    displayName = item.originalName,
                    mediaType = item.mediaType,
                    sizeBytes = item.sizeBytes,
                    dateTaken = System.currentTimeMillis(),
                    sha256Hash = normalizedHash,
                    stagingPath = tempFile.absolutePath,
                    state = BackupState.VERIFIED,
                    bytesTransferred = item.sizeBytes,
                    verifiedAt = System.currentTimeMillis(),
                    retentionExpiresAt = null, // Restored files are permanent; never cleanup-eligible
                    isManualFile = item.storageCategory == "Files"
                )

                dbHelper.insertOrIgnore(dbItem)
                restoredCount++
            } finally {
                // Remove temp part
                if (tempFile.exists()) tempFile.delete()
            }
        }

        Pair(restoredCount, skippedCount)
    }

    private fun insertIntoMediaStore(sourceFile: File, originalName: String, mediaType: String, category: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaType)
            put(MediaStore.MediaColumns.SIZE, sourceFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                val relativePath = when (category) {
                    "Videos" -> "${Environment.DIRECTORY_MOVIES}/Restored"
                    "Files" -> "${Environment.DIRECTORY_DOWNLOADS}/Restored"
                    else -> "${Environment.DIRECTORY_PICTURES}/Restored"
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
        }

        val targetCollection = when (category) {
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(targetCollection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
