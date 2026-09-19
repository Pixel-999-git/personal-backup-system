# Testing Guide

The Personal Backup System includes automated unit, integration, and simulated end-to-end failure injection test suites.

---

## 1. Automated Windows Test Suite

Runs all unit and integration tests for the Windows service using Node's built-in native test runner:

```powershell
cd windows-service
npm test
```

### What is Tested:
1. **Path Traversal Security**: Verifies that malicious paths (`../../etc/passwd`, `..\..\Windows\System32\cmd.exe`) are cleanly sanitized without allowing traversal.
2. **Chunked Resumable Upload**: Writes chunks sequentially, tests offset gap rejection, and validates final cryptographic digest.
3. **Tamper Detection & Quarantine**: Corrupts payload in transit; verifies server rejects commit and moves file to `quarantine/`.
4. **Deduplication**: Uploads same binary data under different names; verifies target blob is stored only once with zero wasted disk space.
5. **Database WAL Persistence**: Simulates service crash and restart; verifies upload sessions and manifest records survive cleanly.
6. **End-to-End HTTP Server**: Verifies pairing, token authentication, resumable chunk streaming, finalization, and reconciliation manifest endpoints.

---

## 2. End-to-End Acceptance & Red Team Suite

Executes realistic network drops, simulated phone crashes, mid-stream chunk interruption, and malicious requests:

```powershell
node scripts/e2e-test.js
```

### Scenario Breakdown:
- **Health check**: Confirms service status and disk space reporting.
- **Pairing rejection**: Rejects unauthorized pairing requests.
- **Security rejection**: Rejects forged or missing authorization tokens (HTTP 401/403).
- **Interruption at 50%**: Simulates network drop midway through a video upload, restarts transfer, confirms resume from byte offset `N`.
- **Integrity mismatch**: Deliberately tampers with bits in transit; verifies HTTP 422 and automatic quarantine.
- **Content Deduplication**: Verifies that uploading an existing file requires zero network transfer bytes.
- **Manual document upload**: Verifies PDF upload via SAF flow.
- **Reconciliation audit**: Confirms all committed archive records appear in daily reconciliation.

---

## 3. Physical Device Verification (Samsung Galaxy A05s)

When the Samsung Galaxy A05s is connected via USB:
1. Ensure USB debugging is enabled on the phone.
2. Verify ADB connection:
   ```powershell
   adb devices -l
   ```
3. Install the APK:
   ```powershell
   adb install -r release\android\personal-backup-v1.0.0.apk
   ```
4. Launch the app and take a sample photo in the camera app.
5. Inspect the Windows archive: verify the photo is transferred to `Archive/media/` and recorded in `Archive/manifests/backup.db`.
