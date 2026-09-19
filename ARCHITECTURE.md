# System Architecture

## Overview

The Personal Backup System is designed as a dual-component architecture consisting of an Android background client and a persistent Windows archive daemon.

```
+-------------------------------------------------------------+
|                      Android Device                         |
|  (Samsung Galaxy A05s / Android 14 / minSdk 26, targetSdk 34)|
|                                                             |
|  [ UI: Minimalist Status, One-Tap Backup, File Picker ]     |
|                             │                               |
|       ┌─────────────────────┼───────────────────────┐       |
|       │                     │                       │       |
|  [ MediaStore Observer ] [ BackupService ]      [ SAF Picker ]
|       │                     │                       │       |
|       └──────────────────► [ SQLite DB ] ◄──────────┘       |
|                       (Durable Phone Queue)                 |
|                             │                               |
|                  [ 24h Staging Directory ]                  |
|                             │                               |
|              [ Resumable Chunk Uploader (OkHttp) ]          |
+─────────────────────────────┼───────────────────────────────+
                              │ Encrypted HTTP / Private WireGuard / Tailscale
                              ▼
+─────────────────────────────────────────────────────────────+
|                    Windows 11 Laptop                        |
|  (Permanent Storage Archive & Windows Background Service)   |
|                                                             |
|          [ Node.js 25 / HTTP Server Daemon ]                |
|           - Device Token Auth & Replay Filter               |
|           - Resumable Chunk Session Manager                 |
|           - Streaming SHA-256 Verifier                      |
|           - Atomic Commit & Quarantining                    |
|                             │                               |
|                     [ SQLite Manifest ]                     |
|           - File Identity, Deduplication Index              |
|           - Archive History, Reconciliation Engine          |
|                             │                               |
|                  [ Archive Directory Structure ]            |
|                  Archive/                                   |
|                    ├── media/        (Content-addressed)    |
|                    ├── files/        (Manual uploads)       |
|                    ├── manifests/    (backup.db)            |
|                    ├── staging/      (In-flight chunks)     |
|                    ├── quarantine/   (Hash mismatches)      |
|                    └── logs/         (Rotated logs)         |
+─────────────────────────────────────────────────────────────+
```

---

## 1. Phone Durable Queue & State Machine

The phone uses Android's embedded SQLite database in Write-Ahead Logging (WAL) mode (`backup_queue.db`).
The queue lifecycle is modeled as an explicit state machine:

- `DISCOVERED`: Detected in MediaStore or SAF picker.
- `STAGED`: Copied into application-private storage (`staging/{sha256}.part`) and SHA-256 calculated.
- `QUEUED`: Enqueued in SQLite database; persists across phone reboots and app updates.
- `UPLOADING`: Actively transferring chunks to Windows host.
- `VERIFIED`: Windows host has received all bytes, confirmed SHA-256 match, committed file atomically, and acknowledged success.
- `CLEANUP_ELIGIBLE`: Staging copy has been retained for at least 24 hours post-verification.
- `CLEANED`: Local staging copy safely deleted; database record preserved for reconciliation.
- `FAILED_RETRYABLE`: Encountered network interruption; retry counter incremented.

---

## 2. Windows Archive File Layout

To prevent filesystem slowdowns and filename collisions, files are organized content-addressed by their SHA-256 digest:

```
Archive/
  ├── media/
  │   ├── a1/
  │   │   └── a1b2c3d4...jpg
  │   └── f9/
  │       └── f9e8d7c6...mp4
  ├── files/
  │   └── 5c/
  │       └── 5ce0e5..._contract.pdf
  ├── staging/
  │   └── {sessionId}.part
  ├── quarantine/
  │   └── mismatch_{timestamp}_{originalName}.corrupt
  └── manifests/
      └── backup.db
```

---

## 3. Resumable Chunk Transfer Protocol

1. **Init**: Client sends `POST /api/v1/upload/init` with `contentHash`, `totalBytes`, `originalName`.
2. **Deduplication Check**: If `contentHash` already exists in `files` table, server returns `alreadyArchived: true`. No bytes transferred.
3. **Session Check**: If an active session exists for this hash, server checks staging file length and returns `receivedBytes: N`. Client seeks to offset `N` and resumes.
4. **Chunk Stream**: Client sends `POST /api/v1/upload/chunk/{sessionId}` with headers `X-Offset` and raw binary body. Server verifies `offset == currentLength` to prevent chunk gaps, writes to disk, and calls `fdatasync`.
5. **Finalize**: Client sends `POST /api/v1/upload/finalize/{sessionId}` with `expectedSha256`.
6. **Integrity & Atomic Commit**: Server calculates streaming SHA-256 of the complete staging file.
   - If hash matches: atomically moves file into `media/` or `files/`, records in SQLite DB, and returns HTTP 200.
   - If hash mismatches: immediately moves file to `quarantine/`, does NOT commit, and returns HTTP 422.
