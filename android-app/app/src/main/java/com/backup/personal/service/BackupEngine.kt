package com.backup.personal.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class BackupEngine(private val context: Context) {
    private val dbHelper = BackupDatabaseHelper.getInstance(context)
    private val prefs = PreferencesManager(context)
    private val apiClient = BackupApiClient()
    private val stagingDir = File(context.filesDir, "staging").apply { mkdirs() }

    companion object {
        const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
    }

    suspend fun scanMediaStore(): Int = withContext(Dispatchers.IO) {
        var newItemsCount = 0
        val contentResolver = context.contentResolver

        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN
        )

        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (collectionUri in uris) {
            try {
                contentResolver.query(
                    collectionUri,
                    projections,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val typeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "media_$id"
                        val type = cursor.getString(typeCol) ?: "image/jpeg"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol)
                        val contentUri = ContentUris.withAppendedId(collectionUri, id)

                        if (size <= 0) continue

                        // Stage and hash the media file safely into private app storage
                        val tempFile = File(stagingDir, "temp_${UUID.randomUUID()}.part")
                        try {
                            var computedHash: String? = null
                            contentResolver.openInputStream(contentUri)?.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    val buffer = ByteArray(64 * 1024)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        output.write(buffer, 0, read)
                                        digest.update(buffer, 0, read)
                                    }
                                    computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                                }
                            }

                            if (computedHash != null) {
                                val persistentStagingFile = File(stagingDir, "${computedHash}.part")
                                if (!persistentStagingFile.exists()) {
                                    tempFile.renameTo(persistentStagingFile)
                                } else {
                                    tempFile.delete()
                                }

                                val item = BackupItem(
                                    id = UUID.randomUUID().toString(),
                                    uriString = contentUri.toString(),
                                    displayName = name,
                                    mediaType = type,
                                    sizeBytes = size,
                                    dateTaken = if (date > 0) date else System.currentTimeMillis(),
                                    sha256Hash = computedHash,
                                    stagingPath = persistentStagingFile.absolutePath,
                                    state = BackupState.QUEUED,
                                    isManualFile = false
                                )

                                if (dbHelper.insertOrIgnore(item)) {
                                    newItemsCount++
                                }
                            }
                        } catch (e: Exception) {
                            tempFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // Scoped storage query permission check
            }
        }

        newItemsCount
    }

    suspend fun stageManualFile(
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        mimeType: String,
        provenance: String = "MANUAL"
    ): Boolean =
        withContext(Dispatchers.IO) {
            val tempFile = File(stagingDir, "temp_${UUID.randomUUID()}.part")
            try {
                var computedHash: String? = null
                val openStream = if (uri.scheme == "file" && uri.path != null) {
                    FileInputStream(File(uri.path!!))
                } else {
                    context.contentResolver.openInputStream(uri)
                }

                openStream?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }

                if (computedHash != null) {
                    val persistentStagingFile = File(stagingDir, "${computedHash}.part")
                    if (!persistentStagingFile.exists()) {
                        tempFile.renameTo(persistentStagingFile)
                    } else {
                        tempFile.delete()
                    }

                    val item = BackupItem(
                        id = UUID.randomUUID().toString(),
                        uriString = uri.toString(),
                        displayName = displayName,
                        mediaType = mimeType,
                        sizeBytes = if (sizeBytes > 0) sizeBytes else persistentStagingFile.length(),
                        dateTaken = System.currentTimeMillis(),
                        sha256Hash = computedHash,
                        stagingPath = persistentStagingFile.absolutePath,
                        state = BackupState.QUEUED,
                        isManualFile = true,
                        provenance = provenance
                    )
                    return@withContext dbHelper.insertOrIgnore(item)
                }
            } catch (e: Exception) {
                tempFile.delete()
            }
            false
        }

    suspend fun stageManualFilesBatch(
        items: List<com.backup.personal.recovery.DiscoveredRecoveryItem>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val backupItems = mutableListOf<BackupItem>()
        val total = items.size

        for ((index, item) in items.withIndex()) {
            val uri = item.uri
            try {
                val tempFile = File(stagingDir, "temp_${UUID.randomUUID()}.part")
                val openStream = if (uri.scheme == "file" && uri.path != null) {
                    FileInputStream(File(uri.path!!))
                } else {
                    context.contentResolver.openInputStream(uri)
                }

                var computedHash: String? = null
                openStream?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }

                if (computedHash != null) {
                    val persistentStagingFile = File(stagingDir, "${computedHash}.part")
                    if (!persistentStagingFile.exists()) {
                        tempFile.renameTo(persistentStagingFile)
                    } else {
                        tempFile.delete()
                    }

                    backupItems.add(
                        BackupItem(
                            id = UUID.randomUUID().toString(),
                            uriString = uri.toString(),
                            displayName = item.displayName,
                            mediaType = item.mimeType,
                            sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else persistentStagingFile.length(),
                            dateTaken = System.currentTimeMillis(),
                            sha256Hash = computedHash,
                            stagingPath = persistentStagingFile.absolutePath,
                            state = BackupState.QUEUED,
                            isManualFile = true,
                            provenance = item.provenance
                        )
                    )
                }
            } catch (_: Exception) {}

            if (index % 50 == 0 || index == total - 1) {
                onProgress?.invoke(index + 1, total)
            }
        }

        val inserted = dbHelper.insertOrIgnoreBatch(backupItems)
        inserted
    }

    suspend fun processQueue(
        onProgress: ((item: BackupItem, transferred: Long, total: Long) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext 0
        val token = prefs.authToken ?: return@withContext 0
        val host = prefs.serverHost
        val port = prefs.serverPort

        val health = apiClient.checkHealth(host, port)
        if (health == null || !health.isHealthy) {
            return@withContext 0 // Host offline or storage low
        }

        val queue = dbHelper.getPendingQueue()
        var successCount = 0

        for (item in queue) {
            val stagingFile = File(item.stagingPath)
            if (!stagingFile.exists()) {
                dbHelper.updateState(item.id, BackupState.FAILED_RETRYABLE, "Staging file missing on device")
                continue
            }

            dbHelper.updateState(item.id, BackupState.UPLOADING)

            try {
                // 1. Initialize upload session
                val initResult = apiClient.initUpload(host, port, token, item)

                if (initResult.alreadyArchived || initResult.isDuplicate) {
                    // Deduplication hit! File already confirmed on server
                    val retentionExp = System.currentTimeMillis() + TWENTY_FOUR_HOURS_MS
                    dbHelper.updateState(
                        id = item.id,
                        state = BackupState.VERIFIED,
                        verifiedAt = System.currentTimeMillis(),
                        retentionExpiresAt = retentionExp
                    )
                    dbHelper.updateProgress(item.id, item.sizeBytes)
                    onProgress?.invoke(item, item.sizeBytes, item.sizeBytes)
                    successCount++
                    continue
                }

                // 2. Resumable Chunk Streaming
                val startOffset = initResult.receivedBytes
                val chunkSize = if (initResult.chunkSizeRecommended > 0) initResult.chunkSizeRecommended else 2 * 1024 * 1024
                var currentOffset = startOffset
                val totalLength = stagingFile.length()

                RandomAccessFile(stagingFile, "r").use { raf ->
                    raf.seek(currentOffset)
                    val buffer = ByteArray(chunkSize)

                    while (currentOffset < totalLength) {
                        val toRead = Math.min(buffer.size.toLong(), totalLength - currentOffset).toInt()
                        val bytesRead = raf.read(buffer, 0, toRead)
                        if (bytesRead <= 0) break

                        val chunkPayload = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        currentOffset = apiClient.uploadChunk(
                            host, port, token, initResult.sessionId, currentOffset, chunkPayload
                        )

                        dbHelper.updateProgress(item.id, currentOffset)
                        onProgress?.invoke(item, currentOffset, totalLength)
                    }
                }

                // 3. Finalize and verify integrity
                val verified = apiClient.finalizeUpload(
                    host, port, token, initResult.sessionId, item.sha256Hash
                )

                if (verified) {
                    val retentionExp = System.currentTimeMillis() + TWENTY_FOUR_HOURS_MS
                    dbHelper.updateState(
                        id = item.id,
                        state = BackupState.VERIFIED,
                        verifiedAt = System.currentTimeMillis(),
                        retentionExpiresAt = retentionExp
                    )
                    successCount++
                } else {
                    dbHelper.incrementRetry(item.id, "Server integrity verification failed")
                }
            } catch (e: Exception) {
                dbHelper.incrementRetry(item.id, e.message ?: "Network transfer error")
            }
        }

        // Run retention cleanup for eligible expired items
        enforceStagingRetention()

        successCount
    }

    suspend fun enforceStagingRetention(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val eligible = dbHelper.getEligibleForCleanup(now)
        var cleanedCount = 0

        for (item in eligible) {
            val file = File(item.stagingPath)
            if (file.exists()) {
                if (file.delete()) {
                    dbHelper.updateState(item.id, BackupState.CLEANED)
                    cleanedCount++
                }
            } else {
                dbHelper.updateState(item.id, BackupState.CLEANED)
                cleanedCount++
            }
        }

        cleanedCount
    }

    suspend fun reconcileWithServer(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.isPaired) return@withContext false
        val token = prefs.authToken ?: return@withContext false
        val host = prefs.serverHost
        val port = prefs.serverPort

        try {
            val serverManifest = apiClient.getReconcileManifest(host, port, token)
            val serverHashes = serverManifest.map { it.contentHash.lowercase() }.toSet()

            val allLocalItems = dbHelper.getAllItems()
            for (local in allLocalItems) {
                if (serverHashes.contains(local.sha256Hash.lowercase())) {
                    if (local.state != BackupState.VERIFIED && local.state != BackupState.CLEANED) {
                        val retentionExp = System.currentTimeMillis() + TWENTY_FOUR_HOURS_MS
                        dbHelper.updateState(
                            id = local.id,
                            state = BackupState.VERIFIED,
                            verifiedAt = System.currentTimeMillis(),
                            retentionExpiresAt = retentionExp
                        )
                    }
                }
            }
            prefs.lastReconciliationTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            false
        }
    }
}
