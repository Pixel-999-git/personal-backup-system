# Honest Platform Limitations & Storage Forensic Analysis
**Target Device: Samsung Galaxy A05s (SM-A057F/DS) • Android 14**

---

## 1. Storage Architecture & Deleted-Media Forensic Reality

Many commercial PC tools and consumer recovery apps make exaggerated claims like *"Recover all deleted photos on Android without root!"*. On modern Android 14 with hardware-encrypted storage, such claims are mathematically and architecturally false.

Below is the verified, technically grounded reality of Android 14 storage forensics based on official AOSP specifications and forensic flash storage telemetry:

| Recovery Vector | Capability on Android 14 (Non-Root) | Root Privileged (`su`) | Forensic / Technical Explanation |
| :--- | :--- | :--- | :--- |
| **System MediaStore Trash (`IS_TRASHED`)** | **100% Supported** | 100% Supported | Android 11+ retains deleted media in a 30-day soft-delete holding area. Our app scans `MediaStore.Images`, `MediaStore.Video`, and `MediaStore.Audio` with `QUERY_ARG_MATCH_TRASHED` to archive files before 30-day purge. |
| **Samsung One UI Vendor Trash** | **100% Supported** | 100% Supported | Samsung Gallery & MyFiles move deleted items to `/DCIM/.trash`, `/Pictures/.trash`, and `.recycle`. Our app scans and extracts intact originals before deletion. |
| **Social & App Media Caches (Intact Copies)** | **100% Supported** | 100% Supported | Copies of deleted photos/videos sent or received via WhatsApp (`WhatsApp Images/Sent`), Telegram, or Screenshots persist in readable shared storage directories. |
| **Thumbnail & EXIF Previews** | **100% Supported** | 100% Supported | Scans persistent `/DCIM/.thumbnails/` and carves embedded JPEG previews from image headers using `ExifInterface`. Labeled honestly as `[RECOVERED_THUMBNAIL]`. |
| **Removable microSD Card Remnants** | **100% Supported** | 100% Supported | Removable SD cards format with FAT32/exFAT: **No FBE encryption and no TRIM command**. Our app detects external mounts and carves `LOST.DIR` chunks using file signature magic bytes. |
| **Raw Unallocated Flash Sector Carving** | **NOT POSSIBLE** | Highly Constrained | Accessing raw block devices (`/dev/block/by-name/userdata`) requires Linux `root` UID 0 and modifying SELinux policy. A standard sideloaded Android app cannot read unallocated sectors. |
| **File-Based Encryption (FBE)** | **Cryptographic Barrier** | Cryptographic Barrier | Android 14 uses per-file AES-256 encryption. When a file is permanently deleted, its unique per-inode encryption key in the Linux kernel keyring is discarded. Raw flash bytes become cryptographically indistinguishable from white noise. |
| **Flash TRIM & Wear-Leveling** | **Physical Erasure** | Physical Erasure | Modern UFS flash memory controllers run scheduled `fstrim` and background wear-leveling garbage collection. Freed flash blocks are actively zeroed/erased by the NAND controller during idle charging states. |

---

## 2. Internal NAND Flash vs Removable microSD Cards

A critical forensic distinction exists between phone internal storage and external microSD cards:

1. **Internal Storage (`/data`)**:
   - Filesystem: `ext4` or `F2FS`.
   - Security: Protected by File-Based Encryption (FBE) tied to device Keystore and user lockscreen PIN/pattern.
   - Deletion Behavior: Unlinked immediately; encryption keys wiped; blocks scheduled for `fstrim` wipe.
   - Conclusion: Truly deleted files on internal memory cannot be carved without root, and even with root are rapidly erased or encrypted beyond recovery.
2. **Removable microSD Card (`/storage/<UUID>`)**:
   - Filesystem: `FAT32` or `exFAT`.
   - Security: Unencrypted plaintext (unless explicitly formatted as adoptable storage).
   - Deletion Behavior: Directory entry marked `0xE5`; FAT allocation table entries zeroed. **No TRIM command is issued**, meaning contiguous file clusters persist physically on the flash card until overwritten by new files.
   - Conclusion: Carving intact media from `LOST.DIR` and deleted clusters is highly effective on microSD cards.

---

## 3. Google Photos Locked Folder / Protected Media

- **Security Isolation**: Google Photos stores Locked Folder items in an isolated, private, hardware-backed encrypted vault (`/data/user/0/com.google.android.apps.photos/`).
- Neither MediaStore nor standard Android file providers expose Locked Folder contents to any third-party app.
- **Architectural Guarantee**: Inaccessible by Android security sandbox design. The application does not claim to back up or recover items in Google Photos Locked Folders.

---

## 4. Background Execution & Samsung One UI Battery Optimization

- Android 14 enforces strict background process limits to prevent battery drain.
- To prevent background backup termination during large uploads or when the screen turns off:
  1. The app runs an official Android **Foreground Service** with `foregroundServiceType="dataSync"` and a discrete ongoing notification.
  2. Uses `WorkManager` with exponential retry policies.
  3. Registers `RECEIVE_BOOT_COMPLETED` to resume queue processing after device restart.
- **Recommended User Setting on Samsung Galaxy A05s**:
  Set Personal Backup's battery setting to **Unrestricted** (*Settings → Apps → Personal Backup → Battery → Unrestricted*).

---

## 5. Summary: Backup Over Recovery

> **FORENSIC TAKEAWAY**: On modern encrypted Android devices, file recovery is a fragile, best-effort diagnostic measure. Continuous automated backup to the permanent Windows archive is the **only guaranteed defense** against photo and video loss.
