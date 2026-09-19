# Sideload Auto-Update Mechanism

The Personal Backup application is distributed outside the Google Play Store as a personal sideloaded appliance. This document describes the secure in-app update mechanism.

---

## 1. How Auto-Update Works

1. **Developer Pushes Update on Laptop**:
   - The developer builds a new APK version (`./gradlew assembleRelease` or `assembleDebug`).
   - Copies the new APK into `Archive/updates/app-release.apk`.
   - Updates `Archive/updates/version.json` with the new version code and SHA-256 hash.

2. **Android App Checks for Updates**:
   - Every 24 hours during daily reconciliation (or on app launch), the Android app queries `GET /api/v1/updates/check`.
   - If `versionCode > currentAppVersionCode`, the app notifies the user that an update is available.

3. **Secure Download & Signature Verification**:
   - The APK is downloaded into private app storage (`cache/update.apk`).
   - The app verifies that the downloaded APK's SHA-256 matches `version.json`.
   - If verification passes, the app launches the Android package installer.

---

## 2. Android 14 Operating System Permissions & Interaction

> **IMPORTANT**: On Android 14 without root or Device Owner enterprise MDM management, Android **strictly requires user confirmation** before installing or updating any sideloaded application.

1. When the update is ready, the system displays the standard Android confirmation prompt: *"Do you want to update this app?"*.
2. The user taps **Update**.
3. All local SQLite database queues, preferences, pairing tokens, and pending upload staging files are **strictly preserved** across application updates because the package ID (`com.backup.personal`) and signing key remain identical.
