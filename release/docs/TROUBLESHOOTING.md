# Troubleshooting Guide

Diagnoses and resolutions for common issues.

---

## 1. Phone Shows "Windows Laptop: OFFLINE"

### Causes:
1. **Windows Backup Service is not running**:
   - Check if service is active:
     ```powershell
     Get-Process -Name "node" -ErrorAction SilentlyContinue
     ```
   - Restart the service:
     ```powershell
     .\scripts\start-service.ps1
     ```
2. **Firewall Blocking Port 8976**:
   - Allow incoming traffic on port 8976 in Windows Defender Firewall:
     ```powershell
     New-NetFirewallRule -DisplayName "Personal Backup Service" -Direction Inbound -LocalPort 8976 -Protocol TCP -Action Allow
     ```
3. **Different Network / Tailscale Disconnected**:
   - If outside home Wi-Fi, ensure Tailscale is connected on both Windows and Android.
   - Verify ping from Android phone to Windows IP address.

---

## 2. Pairing Fails ("Invalid pairing code")

- Confirm the pairing code entered in the Android app matches `pairingCode` in `windows-service/config.json`.
- Default code is `BACKUP-7749`.
- If you customized the code, restart the Windows service for changes to take effect.

---

## 3. Large Video Stalled or Stuck

- The client uses resumable chunk streaming. If your connection drops, the app will pause and retry when connectivity returns.
- Tapping **Back Up Everything** on the main dashboard forces an immediate scan and resume of all in-progress queue items.

---

## 4. Insufficient Disk Space on Windows Archive Drive

- The service enforces a minimum free disk space floor (default: 1 GB) to prevent storage exhaustion or database corruption.
- When disk space drops below this threshold, the server rejects new uploads and reports `isHealthy: false` to the phone app.
- Free up disk space on the archive drive; the service will automatically resume accepting uploads.

---

## 5. Corrupted Archive Records or Database Discrepancies

- Run the offline archive audit script:
  ```powershell
  .\scripts\verify-archive.ps1
  ```
- This tool re-hashes all files in `media/` and `files/` against the SQLite manifest and outputs a detailed health report.
