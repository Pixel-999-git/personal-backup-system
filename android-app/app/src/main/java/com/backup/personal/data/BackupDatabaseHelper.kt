package com.backup.personal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BackupDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "backup_queue.db"
        const val DATABASE_VERSION = 2

        const val TABLE_ITEMS = "backup_items"
        const val COL_ID = "id"
        const val COL_URI = "uri_string"
        const val COL_NAME = "display_name"
        const val COL_TYPE = "media_type"
        const val COL_SIZE = "size_bytes"
        const val COL_DATE_TAKEN = "date_taken"
        const val COL_HASH = "sha256_hash"
        const val COL_STAGING_PATH = "staging_path"
        const val COL_STATE = "state"
        const val COL_TRANSFERRED = "bytes_transferred"
        const val COL_RETRIES = "retry_count"
        const val COL_ERROR = "last_error"
        const val COL_CREATED_AT = "created_at"
        const val COL_VERIFIED_AT = "verified_at"
        const val COL_RETENTION_EXPIRES = "retention_expires_at"
        const val COL_IS_MANUAL = "is_manual_file"
        const val COL_PROVENANCE = "provenance"

        @Volatile
        private var instance: BackupDatabaseHelper? = null

        fun getInstance(context: Context): BackupDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: BackupDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ITEMS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_URI TEXT NOT NULL,
                $COL_NAME TEXT NOT NULL,
                $COL_TYPE TEXT NOT NULL,
                $COL_SIZE INTEGER NOT NULL,
                $COL_DATE_TAKEN INTEGER NOT NULL,
                $COL_HASH TEXT NOT NULL,
                $COL_STAGING_PATH TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_TRANSFERRED INTEGER NOT NULL DEFAULT 0,
                $COL_RETRIES INTEGER NOT NULL DEFAULT 0,
                $COL_ERROR TEXT,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_VERIFIED_AT INTEGER,
                $COL_RETENTION_EXPIRES INTEGER,
                $COL_IS_MANUAL INTEGER NOT NULL DEFAULT 0,
                $COL_PROVENANCE TEXT NOT NULL DEFAULT 'ORIGINAL'
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_items_hash ON $TABLE_ITEMS ($COL_HASH);")
        db.execSQL("CREATE INDEX idx_items_state ON $TABLE_ITEMS ($COL_STATE);")
        db.execSQL("CREATE INDEX idx_items_provenance ON $TABLE_ITEMS ($COL_PROVENANCE);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_ITEMS ADD COLUMN $COL_PROVENANCE TEXT NOT NULL DEFAULT 'ORIGINAL'")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_provenance ON $TABLE_ITEMS ($COL_PROVENANCE);")
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun insertOrIgnore(item: BackupItem): Boolean {
        val db = writableDatabase
        // Check if hash already exists
        db.rawQuery("SELECT $COL_ID FROM $TABLE_ITEMS WHERE $COL_HASH = ? LIMIT 1", arrayOf(item.sha256Hash)).use { cursor ->
            if (cursor.moveToFirst()) {
                return false // Already recorded
            }
        }

        val values = ContentValues().apply {
            put(COL_ID, item.id)
            put(COL_URI, item.uriString)
            put(COL_NAME, item.displayName)
            put(COL_TYPE, item.mediaType)
            put(COL_SIZE, item.sizeBytes)
            put(COL_DATE_TAKEN, item.dateTaken)
            put(COL_HASH, item.sha256Hash)
            put(COL_STAGING_PATH, item.stagingPath)
            put(COL_STATE, item.state.name)
            put(COL_TRANSFERRED, item.bytesTransferred)
            put(COL_RETRIES, item.retryCount)
            put(COL_ERROR, item.lastError)
            put(COL_CREATED_AT, item.createdAt)
            put(COL_VERIFIED_AT, item.verifiedAt)
            put(COL_RETENTION_EXPIRES, item.retentionExpiresAt)
            put(COL_IS_MANUAL, if (item.isManualFile) 1 else 0)
            put(COL_PROVENANCE, item.provenance)
        }
        return db.insertWithOnConflict(TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun updateState(
        id: String,
        state: BackupState,
        error: String? = null,
        verifiedAt: Long? = null,
        retentionExpiresAt: Long? = null
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATE, state.name)
            if (error != null) put(COL_ERROR, error)
            if (verifiedAt != null) put(COL_VERIFIED_AT, verifiedAt)
            if (retentionExpiresAt != null) put(COL_RETENTION_EXPIRES, retentionExpiresAt)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    @Synchronized
    fun updateProgress(id: String, bytesTransferred: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TRANSFERRED, bytesTransferred)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    @Synchronized
    fun incrementRetry(id: String, error: String) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE $TABLE_ITEMS SET $COL_RETRIES = $COL_RETRIES + 1, $COL_ERROR = ?, $COL_STATE = ? WHERE $COL_ID = ?",
            arrayOf(error, BackupState.FAILED_RETRYABLE.name, id)
        )
    }

    @Synchronized
    fun getPendingQueue(): List<BackupItem> {
        val list = mutableListOf<BackupItem>()
        val db = readableDatabase
        val query = """
            SELECT * FROM $TABLE_ITEMS 
            WHERE $COL_STATE IN ('DISCOVERED', 'STAGED', 'QUEUED', 'UPLOADING', 'FAILED_RETRYABLE')
            ORDER BY $COL_CREATED_AT ASC
        """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToItem(cursor))
            }
        }
        return list
    }

    @Synchronized
    fun getEligibleForCleanup(now: Long): List<BackupItem> {
        val list = mutableListOf<BackupItem>()
        val db = readableDatabase
        val query = """
            SELECT * FROM $TABLE_ITEMS 
            WHERE $COL_STATE = 'VERIFIED' AND $COL_RETENTION_EXPIRES IS NOT NULL AND $COL_RETENTION_EXPIRES <= ?
        """.trimIndent()

        db.rawQuery(query, arrayOf(now.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToItem(cursor))
            }
        }
        return list
    }

    @Synchronized
    fun getStats(): Map<String, Int> {
        val db = readableDatabase
        val map = mutableMapOf<String, Int>()
        val query = "SELECT $COL_STATE, COUNT(*) FROM $TABLE_ITEMS GROUP BY $COL_STATE"
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val state = cursor.getString(0)
                val count = cursor.getInt(1)
                map[state] = count
            }
        }
        return map
    }

    @Synchronized
    fun getAllItems(): List<BackupItem> {
        val list = mutableListOf<BackupItem>()
        val db = readableDatabase
        db.rawQuery("SELECT * FROM $TABLE_ITEMS ORDER BY $COL_CREATED_AT DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToItem(cursor))
            }
        }
        return list
    }

    private fun cursorToItem(cursor: Cursor): BackupItem {
        return BackupItem(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
            uriString = cursor.getString(cursor.getColumnIndexOrThrow(COL_URI)),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
            mediaType = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SIZE)),
            dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DATE_TAKEN)),
            sha256Hash = cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH)),
            stagingPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_STAGING_PATH)),
            state = BackupState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_STATE))),
            bytesTransferred = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TRANSFERRED)),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_RETRIES)),
            lastError = cursor.getString(cursor.getColumnIndexOrThrow(COL_ERROR)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            verifiedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_VERIFIED_AT))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_VERIFIED_AT)),
            retentionExpiresAt = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_RETENTION_EXPIRES))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_RETENTION_EXPIRES)),
            isManualFile = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_MANUAL)) == 1,
            provenance = try {
                val pIdx = cursor.getColumnIndex(COL_PROVENANCE)
                if (pIdx != -1 && !cursor.isNull(pIdx)) cursor.getString(pIdx) else "ORIGINAL"
            } catch (_: Exception) {
                "ORIGINAL"
            }
        )
    }
}
