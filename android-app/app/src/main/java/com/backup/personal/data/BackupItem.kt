package com.backup.personal.data

enum class BackupState {
    DISCOVERED,
    STAGED,
    QUEUED,
    UPLOADING,
    VERIFIED,
    CLEANUP_ELIGIBLE,
    CLEANED,
    FAILED_RETRYABLE
}

data class BackupItem(
    val id: String,
    val uriString: String,
    val displayName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val dateTaken: Long,
    val sha256Hash: String,
    val stagingPath: String,
    val state: BackupState,
    val bytesTransferred: Long = 0L,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val verifiedAt: Long? = null,
    val retentionExpiresAt: Long? = null,
    val isManualFile: Boolean = false,
    val provenance: String = "ORIGINAL"
)

