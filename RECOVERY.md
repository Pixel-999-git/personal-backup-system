# Forensic Recovery & Archive Restoration Guide
**Personal Android (Samsung Galaxy A05s, Android 14) → Windows Automatic Backup System**

---

## 1. Permanent Archive Rule

> **CORE PRINCIPLE**: Deleting a photo, video, or file on your Android phone **NEVER** deletes it from the Windows archive.

The Windows archive serves as a permanent, write-only, cumulative ledger of all media ever uploaded from your device. Even if an item is deleted from the phone gallery, accidentally wiped, or lost during a phone reset, it remains safe in the Windows archive.

---

## 2. Android Storage Architecture & Deletion Lifecycle

Based on low-level analysis of modern Android storage (`ext4`/`F2FS` on internal UFS flash vs `FAT32`/`exFAT` on removable microSD):

```
┌────────────────────────────────────────────────────────────────────────┐
│ Android Device Storage Architecture                                    │
│                                                                        │
│ ┌─────────────────────────────┐      ┌───────────────────────────────┐ │
│ │ Internal Storage (/data)    │      │ Removable microSD (/storage)  │ │
│ │ • ext4 / F2FS Filesystem    │      │ • FAT32 / exFAT Filesystem    │ │
│ │ • File-Based Encryption(FBE)│      │ • Plaintext (No FBE)          │ │
│ │ • Scheduled fstrim (UFS)    │      │ • No TRIM erase command       │ │
│ │ • Scoped Storage & SELinux  │      │ • LOST.DIR orphan clusters    │ │
│ └─────────────────────────────┘      └───────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

### When a file is deleted in Android 14:
1. **User action**: Gallery or Camera app requests file deletion.
2. **MediaStore update**: The system MediaProvider marks the record as trashed (`IS_TRASHED = 1`) or unlinks the MediaStore database row.
3. **Filesystem unlinking**: The directory entry is unlinked, decreasing the inode reference count to zero.
4. **Encryption key discard**: Under Android's File-Based Encryption (FBE), the per-file cryptographic key stored in the Linux kernel keyring is discarded. Raw NAND blocks immediately become cryptographically undecipherable random ciphertext.
5. **UFS TRIM execution**: Periodic `fstrim` signals the flash memory controller to mark deleted blocks for background garbage collection and erase.
6. **Removable SD cards exception**: Removable microSD cards use FAT32/exFAT without FBE and without TRIM. File remnants and `LOST.DIR` cluster chains persist until overwritten by new data.

---

## 3. Multi-Tiered Forensic Recovery Subsystem

To salvage maximum recoverable media without violating Android 14 security rules or making false promises, our recovery engine implements a **5-tier forensic pipeline**:

### Tier 1: System MediaStore Trash (`ORIGINAL_TRASH`)
- Scans `MediaStore.Images`, `MediaStore.Video`, and `MediaStore.Audio` with `QUERY_ARG_MATCH_TRASHED = MATCH_ONLY` and fallback `IS_TRASHED = 1`.
- Recovers full original photos, videos, and voice memos before the 30-day soft-delete expiration.
- Label: `[RECOVERED_TRASH]` → Stored on Windows in `Recovery/Trash/`.

### Tier 2: Samsung One UI & Vendor Trash Folders (`VENDOR_TRASH`)
- Scans Samsung Gallery and MyFiles vendor trash directories:
  - `/sdcard/DCIM/.trash` and `/sdcard/DCIM/Trash`
  - `/sdcard/Pictures/.trash` and `/sdcard/Pictures/Trash`
  - `/sdcard/.recycle` and `/sdcard/MyFiles/.recycle`
- Label: `[RECOVERED_SAMSUNG_TRASH]` → Stored on Windows in `Recovery/Trash/`.

### Tier 3: Social & App Media Caches (`APP_CACHE_COPY`)
- When a user deletes a photo from `DCIM/Camera`, high-resolution intact copies often survive in social media and messaging directories:
  - WhatsApp: `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images` (+ `/Sent`)
  - WhatsApp Video: `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video` (+ `/Sent`)
  - Telegram: `Android/media/org.telegram.messenger/Telegram/Telegram Images`, `Movies/Telegram`
  - Downloads & Screenshots: `Download/`, `Pictures/Screenshots/`
- Label: `[RECOVERED_APP_CACHE]` → Stored on Windows in `Recovery/AppCaches/`.

### Tier 4: Thumbnail & Embedded EXIF Header Carving (`THUMBNAIL_PREVIEW`)
- Persistent previews in `/sdcard/DCIM/.thumbnails/`.
- Embedded EXIF thumbnail carving: Extracts embedded JPEG previews from image headers using Android's `ExifInterface`.
- Label: `[RECOVERED_THUMBNAIL]` → Stored on Windows in `Recovery/Thumbnails/`.

### Tier 5: Removable Storage (microSD FAT32/exFAT) & `LOST.DIR` Carving (`REMOVABLE_STORAGE`)
- Scans secondary removable storage volumes for:
  - `LOST.DIR` orphan clusters: Carves raw file headers using binary magic bytes (`0xFFD8FF` for JPEG, `0x89504E47` for PNG, `ftyp` for MP4).
  - `.Trash-1000` volume recycle bins.
- Label: `[RECOVERED_SDCARD]` or `[RECOVERED_LOSTDIR]` → Stored on Windows in `Recovery/SDCard/`.

---

## 4. Forensic Honesty & Provenance Classification

We adhere strictly to forensic engineering honesty:
- **Never claim a thumbnail is an original photo**: Thumbnails are explicitly labeled with `[RECOVERED_THUMBNAIL]` and routed to `Recovery/Thumbnails/`.
- **Intact copies vs Previews**: Originals from trash and app caches retain full dimensions and metadata.
- **Root boundaries**: On standard unrooted Android 14 devices, raw sector reading (`/dev/block/*`) is forbidden by Linux permissions and SELinux policy. The app dynamically audits root status (`su` binary detection) and reports why raw carving is prevented on unrooted devices.

---

## 5. Windows Archive Directory Structure

On the Windows laptop, recovered files are systematically segregated by provenance:

```
Samsung Galaxy A05s Backup/
├── Photos/               <-- Normal camera photos
├── Videos/               <-- Normal camera videos
├── Files/                <-- Manual documents (PDF, DOCX, ZIP)
├── Recovery/
│   ├── Trash/            <-- [RECOVERED_TRASH] & [RECOVERED_SAMSUNG_TRASH]
│   ├── AppCaches/        <-- [RECOVERED_APP_CACHE] (WhatsApp, Telegram, etc.)
│   ├── Thumbnails/       <-- [RECOVERED_THUMBNAIL] (Previews & EXIF thumbnails)
│   └── SDCard/           <-- [RECOVERED_SDCARD] & [RECOVERED_LOSTDIR]
├── Metadata/
│   └── archive_manifest.db
├── Logs/
├── staging/
└── quarantine/
```

---

## 6. Restoring Files Back to a Phone

### Method A: In-App One-Tap Restore
1. Open the Android application dashboard.
2. Tap **"Restore Archive to This Device"**.
3. The app queries the Windows server for verified archived items and downloads them directly into the phone's gallery (`DCIM/Restored/` or `Pictures/Restored/`).
4. **Re-upload Immunity**: Restored items are recorded in the local SQLite queue as `VERIFIED` with identical SHA-256 hashes, preventing infinite re-upload loops.

### Method B: Replacement / New Phone Setup
1. Install the APK on your replacement Android phone.
2. Tap **"Replacement / New Phone Recovery Setup"** on the dashboard.
3. Enter your Server IP and the **Recovery Key** (`RCV-XXXX-...`) provided during initial pairing.
4. The Windows server authorizes the new phone, binds the archive, and allows full restoration of all photos, videos, and files.

### Method C: Direct Local Access
Because the Windows archive stores all files with human-readable filenames and standard extensions in `Photos/`, `Videos/`, and `Recovery/`, you can copy files directly using a USB cable, USB-C flash drive, or local network share without requiring proprietary decryption tools.
