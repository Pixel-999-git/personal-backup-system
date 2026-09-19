# Security Architecture & Threat Model

The Personal Backup System implements pragmatic personal security without unnecessary enterprise SaaS complexity.

---

## 1. Zero Public Internet Exposure

- The Windows service is designed to listen exclusively on a private network interface (e.g. `127.0.0.1`, LAN, or Tailscale WireGuard overlay).
- No port forwarding or public router openings are required.
- Traffic routed over Tailscale is encrypted end-to-end using standard WireGuard cryptographic protocols (ChaCha20-Poly1305).

---

## 2. Authenticated Device Pairing

- **One-Time Pairing Code**: A secret pairing code is configured in `config.json` (default: `BACKUP-7749`).
- **Cryptographic Device Tokens**: Upon successful pairing, the server generates a high-entropy 256-bit authentication token (`uuid-uuid`).
- **Token Hashing**: The server stores only the SHA-256 hash of the device token in its SQLite database (`paired_devices` table). Even if the server database were read, original plaintext tokens are never exposed.
- **Unauthorized Rejection**: Any upload or manifest request lacking a valid `Authorization: Bearer <token>` header is rejected with HTTP 401/403.

---

## 3. Path Traversal & File Injection Defense

- Filenames provided by client devices are strictly sanitized using `ArchiveManager.sanitizeFilename()`.
- Directory traversal sequences (`..`, `/`, `\`), null bytes, and control characters are stripped.
- Files are placed into content-addressed subdirectories using the first two characters of their SHA-256 hash (`Archive/media/ab/ab1234...jpg`). Arbitrary remote file overwrites or execution are impossible.

---

## 4. End-to-End Cryptographic Integrity

- Media files are transferred in resumable chunks.
- Upon completion, the server calculates the full SHA-256 digest of the assembled file on disk.
- If the computed digest does not match the client's expected hash, the file is immediately quarantined to `Archive/quarantine/` and never admitted into the active archive or marked verified in the database.
