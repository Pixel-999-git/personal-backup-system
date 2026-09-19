# Personal Android → Windows Automatic Backup & Recovery System

A production-quality personal backup appliance built for one user to automatically and permanently preserve photos, videos, and manual documents from an Android device (tested target: Samsung Galaxy A05s, Android 14) to a dedicated Windows laptop archive.

---

## Key Highlights

- **Zero Intentional Data Loss**: Media is staged into private durable app storage and never removed until verified in the Windows archive.
- **24-Hour Safety Retention Window**: Phone staging copies are retained for a minimum of 24 hours *after* verified archival before becoming eligible for automatic cleanup.
- **Permanent Windows Archive**: Delete on phone **never** deletes from the Windows archive. The archive is a historical repository, not a mirror.
- **Resumable Chunk Streaming**: Large files and multi-gigabyte 4K videos survive Wi-Fi drops, phone restarts, and Windows reboots, resuming from the exact byte offset.
- **Content-Addressed Deduplication**: Identical media discovered multiple times or renamed is recognized by SHA-256 digest, preventing duplicate storage while mapping original file metadata.
- **End-to-End Cryptographic Integrity**: Verifies SHA-256 checksums before committing files. Corrupt transfers are automatically rejected and quarantined.
- **Daily Reconciliation**: Automatically audits phone and Windows manifests every 24 hours to catch and queue any items missed during network drops.
- **Manual File Backup**: Back up arbitrary documents (PDFs, ZIPs, spreadsheets) via Android's Storage Access Framework.
- **Honest Deleted-Media Recovery**: Implements non-root recoverable source recovery (MediaStore Trash / `IS_TRASHED` and thumbnail remnants) while clearly documenting Android 14 platform boundaries.
- **Zero Cloud Database Required**: Direct encrypted overlay transport (via Tailscale / WireGuard or private LAN) without exposing public open ports or storing personal data in third-party clouds.

---

## Directory Structure

```
personal-backup-system/
├── android-app/                   # Android Client (Kotlin / Compose / SDK 34)
│   ├── app/src/main/
│   │   ├── java/com/backup/personal/
│   │   │   ├── data/              # BackupItem, BackupDatabaseHelper (WAL SQLite)
│   │   │   ├── network/           # BackupApiClient (OkHttp resumable client)
│   │   │   ├── service/           # BackupEngine, BackupService (Foreground dataSync)
│   │   │   ├── recovery/          # DeletedMediaScanner (MediaStore trash & cache)
│   │   │   └── ui/                # MainActivity (Compose dashboard & file picker)
│   │   └── AndroidManifest.xml    # Permissions & service declarations
│   └── build.gradle.kts
│
├── windows-service/               # Windows Server (Node.js 25 / native SQLite WAL)
│   ├── src/
│   │   ├── archive.js             # Atomic staging, sanitization, deduplication
│   │   ├── db.js                  # SQLite database engine (WAL journal)
│   │   ├── server.js              # HTTP REST & chunk streaming server
│   │   ├── config.js              # Configuration & thresholds loader
│   │   └── main.js                # CLI runner & offline audit verifier
│   └── tests/
│       └── archive.test.js        # Unit & integration test suite
│
├── scripts/                       # Automation scripts
│   ├── start-service.ps1          # Start Windows service in background
│   ├── stop-service.ps1           # Stop background service
│   ├── install-service.ps1        # Configure Windows logon autostart
│   ├── verify-archive.ps1         # Run offline SHA-256 archive audit
│   └── e2e-test.js                # Acceptance & Red Team test suite
│
├── release/                       # Installable release artifacts
│   ├── android/                   # personal-backup-v1.0.0.apk (12.4 MB)
│   ├── windows/                   # Windows service package & runner
│   └── scripts/                   # Deployment PowerShell scripts
│
└── docs/                          # Comprehensive system documentation
    ├── ARCHITECTURE.md
    ├── SETUP.md
    ├── TESTING.md
    ├── TROUBLESHOOTING.md
    ├── RECOVERY.md
    ├── UPDATES.md
    ├── SECURITY.md
    └── LIMITATIONS.md
```

---

## Quick Start

### 1. Start Windows Backup Service
Open PowerShell in `windows-service` or run:
```powershell
.\scripts\start-service.ps1
```
The service will start listening on port `8976` (or configured port) and generate `Archive/` structure.

### 2. Install Android App
Sideload `release/android/personal-backup-v1.0.0.apk` onto your phone.

### 3. Pair Device Once
1. Open the Personal Backup app on your phone.
2. Tap **Pairing / Setup**.
3. Enter the Windows laptop IP (or Tailscale IP) and the pairing code (default: `BACKUP-7749`).
4. Tap **Pair Now**. Once paired, background sync is fully automatic!
