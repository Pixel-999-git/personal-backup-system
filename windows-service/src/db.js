const { DatabaseSync } = require('node:sqlite');
const crypto = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');

class Database {
  constructor(dbPath, archiveId, recoveryKeyHash) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    this.db = new DatabaseSync(dbPath);
    this.initSchema(archiveId, recoveryKeyHash);
  }

  initSchema(archiveId, recoveryKeyHash) {
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      PRAGMA foreign_keys = ON;

      CREATE TABLE IF NOT EXISTS archive_info (
        archive_id TEXT PRIMARY KEY,
        recovery_key_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS device_registry (
        device_id TEXT PRIMARY KEY,
        device_name TEXT NOT NULL,
        auth_token_hash TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'ACTIVE',
        registered_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS files (
        id TEXT PRIMARY KEY,
        content_hash TEXT NOT NULL,
        original_name TEXT NOT NULL,
        media_type TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        relative_path TEXT NOT NULL,
        storage_category TEXT NOT NULL DEFAULT 'Photos',
        client_created_at TEXT NOT NULL,
        archived_at TEXT NOT NULL,
        is_manual_file INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_files_hash ON files(content_hash);
      CREATE INDEX IF NOT EXISTS idx_files_archived_at ON files(archived_at);

      CREATE TABLE IF NOT EXISTS upload_sessions (
        session_id TEXT PRIMARY KEY,
        content_hash TEXT NOT NULL,
        original_name TEXT NOT NULL,
        media_type TEXT NOT NULL,
        total_bytes INTEGER NOT NULL,
        received_bytes INTEGER NOT NULL,
        staging_path TEXT NOT NULL,
        is_manual_file INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL,
        client_created_at TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_sessions_hash ON upload_sessions(content_hash);

      CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        event_type TEXT NOT NULL,
        details TEXT NOT NULL,
        timestamp TEXT NOT NULL
      );
    `);

    // Ensure archive_info has entry
    if (archiveId && recoveryKeyHash) {
      const existing = this.db.prepare("SELECT archive_id FROM archive_info LIMIT 1").get();
      if (!existing) {
        const now = new Date().toISOString();
        this.db.prepare("INSERT INTO archive_info (archive_id, recovery_key_hash, created_at) VALUES (?, ?, ?)")
          .run(archiveId, recoveryKeyHash, now);
      }
    }
  }

  getArchiveInfo() {
    const row = this.db.prepare("SELECT archive_id AS archiveId, recovery_key_hash AS recoveryKeyHash, created_at AS createdAt FROM archive_info LIMIT 1").get();
    return row || null;
  }

  verifyRecoveryKey(recoveryKey) {
    if (!recoveryKey || typeof recoveryKey !== 'string') return false;
    const cleanKey = recoveryKey.trim().toUpperCase();
    const hash = crypto.createHash('sha256').update(cleanKey).digest('hex');
    const info = this.getArchiveInfo();
    return info && info.recoveryKeyHash.toLowerCase() === hash.toLowerCase();
  }

  checkFileExists(contentHash) {
    const stmt = this.db.prepare("SELECT 1 FROM files WHERE content_hash = ? AND status = 'VERIFIED' LIMIT 1");
    const row = stmt.get(contentHash.toLowerCase());
    return !!row;
  }

  recordFile({ contentHash, originalName, mediaType, sizeBytes, relativePath, storageCategory, clientCreatedAt, isManualFile }) {
    const id = crypto.randomUUID();
    const now = new Date().toISOString();

    const stmt = this.db.prepare(`
      INSERT INTO files (id, content_hash, original_name, media_type, size_bytes, relative_path, storage_category, client_created_at, archived_at, is_manual_file, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED')
    `);

    stmt.run(
      id,
      contentHash.toLowerCase(),
      originalName,
      mediaType,
      sizeBytes,
      relativePath,
      storageCategory || 'Photos',
      clientCreatedAt || now,
      now,
      isManualFile ? 1 : 0
    );

    this.logAudit('FILE_ARCHIVED', `Archived ${originalName} to ${relativePath} (hash: ${contentHash}, size: ${sizeBytes} bytes)`);
    return id;
  }

  createUploadSession({ sessionId, contentHash, originalName, mediaType, totalBytes, stagingPath, isManualFile, clientCreatedAt }) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare(`
      INSERT INTO upload_sessions (
        session_id, content_hash, original_name, media_type,
        total_bytes, received_bytes, staging_path, is_manual_file,
        status, client_created_at, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, 'IN_PROGRESS', ?, ?, ?)
    `);

    stmt.run(
      sessionId,
      contentHash.toLowerCase(),
      originalName,
      mediaType,
      totalBytes,
      stagingPath,
      isManualFile ? 1 : 0,
      clientCreatedAt || now,
      now,
      now
    );
  }

  getSession(sessionId) {
    const stmt = this.db.prepare(`
      SELECT session_id AS sessionId, content_hash AS contentHash, original_name AS originalName,
             media_type AS mediaType, total_bytes AS totalBytes, received_bytes AS receivedBytes,
             staging_path AS stagingPath, is_manual_file AS isManualFile,
             status, client_created_at AS clientCreatedAt
      FROM upload_sessions WHERE session_id = ?
    `);
    const row = stmt.get(sessionId);
    if (!row) return null;
    return {
      ...row,
      isManualFile: !!row.isManualFile
    };
  }

  findActiveSessionByHash(contentHash) {
    const stmt = this.db.prepare(`
      SELECT session_id AS sessionId, content_hash AS contentHash, original_name AS originalName,
             media_type AS mediaType, total_bytes AS totalBytes, received_bytes AS receivedBytes,
             staging_path AS stagingPath, is_manual_file AS isManualFile,
             status, client_created_at AS clientCreatedAt
      FROM upload_sessions WHERE content_hash = ? AND status = 'IN_PROGRESS' LIMIT 1
    `);
    const row = stmt.get(contentHash.toLowerCase());
    if (!row) return null;
    return {
      ...row,
      isManualFile: !!row.isManualFile
    };
  }

  updateSessionProgress(sessionId, receivedBytes) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare(`
      UPDATE upload_sessions SET received_bytes = ?, updated_at = ? WHERE session_id = ?
    `);
    stmt.run(receivedBytes, now, sessionId);
  }

  completeSession(sessionId) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare(`
      UPDATE upload_sessions SET status = 'COMPLETED', updated_at = ? WHERE session_id = ?
    `);
    stmt.run(now, sessionId);
  }

  registerDevice(deviceId, deviceName, tokenHash) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare(`
      INSERT INTO device_registry (device_id, device_name, auth_token_hash, status, registered_at, last_seen_at)
      VALUES (?, ?, ?, 'ACTIVE', ?, ?)
      ON CONFLICT(device_id) DO UPDATE SET
        device_name = excluded.device_name,
        auth_token_hash = excluded.auth_token_hash,
        status = 'ACTIVE',
        last_seen_at = excluded.last_seen_at
    `);
    stmt.run(deviceId, deviceName, tokenHash, now, now);
    this.logAudit('DEVICE_PAIRED', `Paired device: ${deviceName} (${deviceId})`);
  }

  revokeDevice(deviceId) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare("UPDATE device_registry SET status = 'REVOKED', last_seen_at = ? WHERE device_id = ?");
    stmt.run(now, deviceId);
    this.logAudit('DEVICE_REVOKED', `Revoked device access for device ID: ${deviceId}`);
  }

  listDevices() {
    const stmt = this.db.prepare(`
      SELECT device_id AS deviceId, device_name AS deviceName, status, registered_at AS registeredAt, last_seen_at AS lastSeenAt
      FROM device_registry ORDER BY registered_at DESC
    `);
    return stmt.all();
  }

  verifyDeviceToken(tokenHash) {
    const stmt = this.db.prepare("SELECT device_id AS deviceId, status FROM device_registry WHERE auth_token_hash = ? LIMIT 1");
    const row = stmt.get(tokenHash);
    if (row) {
      if (row.status === 'REVOKED') {
        return false; // Revoked device blocked!
      }
      const now = new Date().toISOString();
      this.db.prepare("UPDATE device_registry SET last_seen_at = ? WHERE auth_token_hash = ?").run(now, tokenHash);
      return true;
    }
    return false;
  }

  getManifestItems() {
    const stmt = this.db.prepare(`
      SELECT content_hash AS contentHash, original_name AS originalName,
             size_bytes AS sizeBytes, relative_path AS relativePath,
             storage_category AS storageCategory, client_created_at AS clientCreatedAt,
             archived_at AS archivedAt, status
      FROM files WHERE status = 'VERIFIED' ORDER BY archived_at DESC
    `);
    return stmt.all();
  }

  getFilesForRestore() {
    const stmt = this.db.prepare(`
      SELECT content_hash AS contentHash, original_name AS originalName,
             size_bytes AS sizeBytes, relative_path AS relativePath,
             media_type AS mediaType, storage_category AS storageCategory
      FROM files WHERE status = 'VERIFIED' ORDER BY archived_at ASC
    `);
    return stmt.all();
  }

  getStats() {
    const stmt = this.db.prepare("SELECT COUNT(*) AS totalFiles, COALESCE(SUM(size_bytes), 0) AS totalBytes FROM files WHERE status = 'VERIFIED'");
    const row = stmt.get();
    return {
      totalFiles: Number(row.totalFiles || 0),
      totalBytes: Number(row.totalBytes || 0)
    };
  }

  logAudit(eventType, details) {
    const now = new Date().toISOString();
    const stmt = this.db.prepare("INSERT INTO audit_log (event_type, details, timestamp) VALUES (?, ?, ?)");
    stmt.run(eventType, details, now);
  }

  close() {
    this.db.close();
  }
}

module.exports = { Database };
