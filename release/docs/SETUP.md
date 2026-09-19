# Setup & Installation Guide

This guide describes the one-time installation and pairing process for the Personal Backup System.

---

## 1. Prerequisites

- **Windows Laptop**:
  - Windows 10 or Windows 11 (64-bit)
  - Node.js 18+ installed (Node 25.8.1 recommended; includes native SQLite)
  - Available disk space (at least 1 GB safety margin)
- **Android Phone**:
  - Target: Samsung Galaxy A05s (SM-A057F/DS) or any Android 8.0+ (API 26–34) device.

---

## 2. Windows Service Setup

1. Open PowerShell as Administrator (or standard user).
2. Navigate to the `windows-service` directory:
   ```powershell
   cd C:\Users\super\.gemini\antigravity-ide\scratch\personal-backup-system\windows-service
   ```
3. Check `config.json` (or let it auto-create with default port `8976` and pairing code `BACKUP-7749`).
4. To register the service to start automatically on Windows boot/login, run:
   ```powershell
   ..\scripts\install-service.ps1
   ```
5. To launch immediately in the background:
   ```powershell
   ..\scripts\start-service.ps1
   ```

---

## 3. Network Connectivity (Zero Public Port Exposure)

The system is designed to work across the Internet without exposing Windows directly to the public web.

### Recommended: Private Overlay Network (Tailscale)
1. Install **Tailscale** on both your Windows laptop and your Android phone.
2. Sign in to the same Tailscale account.
3. Obtain your Windows laptop's Tailscale 100.x.y.z IP address.
4. Use this 100.x.y.z IP in the Android app. Tailscale provides end-to-end WireGuard encryption and seamless NAT traversal.

### Alternative: Local Wi-Fi
If both devices are on the same home Wi-Fi network, obtain your laptop's local LAN IP (e.g. `192.168.1.100`) using `ipconfig`.

---

## 4. Android Application Installation

1. Copy `release/android/personal-backup-v1.0.0.apk` to your phone (via USB, Quick Share, or ADB):
   ```powershell
   adb install -r release\android\personal-backup-v1.0.0.apk
   ```
2. Open the **Personal Backup** app on your phone.
3. Grant the requested media permissions (Photos and Videos).
4. Tap **Pairing / Setup** in the top-right corner.
5. Enter your Windows Laptop IP (LAN or Tailscale IP) and the Pairing Code (`BACKUP-7749`).
6. Tap **Pair Now**.

Once paired, your phone and laptop are permanently linked. Every new photo or video you capture is automatically staged, transferred, and verified in your Windows archive.
