const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

class ArchiveManager {
  constructor(rootDir, minFreeSpaceBytes) {
    this.rootDir = path.resolve(rootDir);
    this.photosDir = path.join(this.rootDir, 'Photos');
    this.videosDir = path.join(this.rootDir, 'Videos');
    this.filesDir = path.join(this.rootDir, 'Files');
    this.metadataDir = path.join(this.rootDir, 'Metadata');
    this.recoveryDir = path.join(this.rootDir, 'Recovery');
    this.recoveryTrashDir = path.join(this.recoveryDir, 'Trash');
    this.recoveryAppCachesDir = path.join(this.recoveryDir, 'AppCaches');
    this.recoveryThumbnailsDir = path.join(this.recoveryDir, 'Thumbnails');
    this.recoverySDCardDir = path.join(this.recoveryDir, 'SDCard');
    this.logsDir = path.join(this.rootDir, 'Logs');
    this.stagingDir = path.join(this.rootDir, 'staging');
    this.quarantineDir = path.join(this.rootDir, 'quarantine');
    this.minFreeSpaceBytes = minFreeSpaceBytes || (1024 * 1024 * 1024);

    this.ensureDirs();
  }

  ensureDirs() {
    [
      this.rootDir,
      this.photosDir,
      this.videosDir,
      this.filesDir,
      this.metadataDir,
      this.recoveryDir,
      this.recoveryTrashDir,
      this.recoveryAppCachesDir,
      this.recoveryThumbnailsDir,
      this.recoverySDCardDir,
      this.logsDir,
      this.stagingDir,
      this.quarantineDir,
    ].forEach((dir) => {
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
    });
  }

  static sanitizeFilename(name) {
    if (!name || typeof name !== 'string') return 'unnamed_file';
    let clean = name.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_');
    clean = clean.replace(/\.{2,}/g, '_'); // prevent '..'
    clean = clean.replace(/_+/g, '_');
    clean = clean.trim().replace(/^[_.]+|[_.]+$/g, '');
    return clean.length > 0 ? clean : 'unnamed_file';
  }

  checkFreeSpace() {
    try {
      const stats = fs.statfsSync(this.rootDir);
      const available = stats.bavail * stats.bsize;
      if (available < this.minFreeSpaceBytes) {
        throw new Error(
          `Insufficient disk space on archive drive. Available: ${Math.round(available / (1024 * 1024))} MB, Minimum required: ${Math.round(this.minFreeSpaceBytes / (1024 * 1024))} MB`
        );
      }
      return available;
    } catch (err) {
      if (err.message.includes('Insufficient disk space')) {
        throw err;
      }
      return Number.MAX_SAFE_INTEGER;
    }
  }

  stagingPathForSession(sessionId) {
    const safeSession = ArchiveManager.sanitizeFilename(sessionId);
    return path.join(this.stagingDir, `${safeSession}.part`);
  }

  writeChunk(stagingPath, offset, chunkBuffer) {
    this.checkFreeSpace();

    let currentLen = 0;
    if (fs.existsSync(stagingPath)) {
      currentLen = fs.statSync(stagingPath).size;
    }

    if (offset > currentLen) {
      throw new Error(
        `Chunk offset gap detected: received offset ${offset} but staging file size is ${currentLen}. Must send sequentially or resume.`
      );
    }

    const fd = fs.openSync(stagingPath, 'a+');
    try {
      fs.writeSync(fd, chunkBuffer, 0, chunkBuffer.length, offset);
      fs.fdatasyncSync(fd);
    } finally {
      fs.closeSync(fd);
    }

    return fs.statSync(stagingPath).size;
  }

  computeSha256(filePath) {
    return new Promise((resolve, reject) => {
      const hash = crypto.createHash('sha256');
      const stream = fs.createReadStream(filePath);
      stream.on('data', (chunk) => hash.update(chunk));
      stream.on('end', () => resolve(hash.digest('hex')));
      stream.on('error', (err) => reject(err));
    });
  }

  determineCategory(originalName, mediaType, isManualFile) {
    if (originalName.startsWith('[RECOVERED') || originalName.startsWith('[THUMBNAIL')) {
      return 'Recovery';
    }
    if (isManualFile) {
      return 'Files';
    }

    const ext = path.extname(originalName).toLowerCase();
    const photoExts = ['.jpg', '.jpeg', '.png', '.heic', '.webp', '.gif', '.dng', '.bmp'];
    const videoExts = ['.mp4', '.mov', '.mkv', '.avi', '.3gp', '.webm', '.flv'];

    if (photoExts.includes(ext) || (mediaType && mediaType.startsWith('image/'))) {
      return 'Photos';
    }
    if (videoExts.includes(ext) || (mediaType && mediaType.startsWith('video/'))) {
      return 'Videos';
    }
    return 'Files';
  }

  async commitUpload(stagingPath, expectedSha256, originalName, mediaType, isManualFile) {
    if (!fs.existsSync(stagingPath)) {
      throw new Error(`Staging file does not exist: ${stagingPath}`);
    }

    // 1. Verify SHA-256 integrity
    const actualHash = await this.computeSha256(stagingPath);
    if (actualHash.toLowerCase() !== expectedSha256.toLowerCase()) {
      const quarantinePath = path.join(
        this.quarantineDir,
        `mismatch_${Date.now()}_${ArchiveManager.sanitizeFilename(originalName)}.corrupt`
      );
      try {
        fs.renameSync(stagingPath, quarantinePath);
      } catch (_) {}

      throw new Error(
        `INTEGRITY VERIFICATION FAILED! Expected SHA-256 '${expectedSha256}', actual '${actualHash}'. Staging copy quarantined to ${quarantinePath}`
      );
    }

    // 2. Select human-browseable destination folder
    const category = this.determineCategory(originalName, mediaType, isManualFile);
    let targetDir = this.filesDir;
    if (category === 'Photos') {
      targetDir = this.photosDir;
    } else if (category === 'Videos') {
      targetDir = this.videosDir;
    } else if (category === 'Recovery') {
      if (originalName.startsWith('[RECOVERED_TRASH]') || originalName.startsWith('[RECOVERED_SAMSUNG_TRASH]')) {
        targetDir = this.recoveryTrashDir;
      } else if (originalName.startsWith('[RECOVERED_APP_CACHE]')) {
        targetDir = this.recoveryAppCachesDir;
      } else if (originalName.startsWith('[RECOVERED_THUMBNAIL]') || originalName.startsWith('[THUMBNAIL_REMNANT]')) {
        targetDir = this.recoveryThumbnailsDir;
      } else if (originalName.startsWith('[RECOVERED_SDCARD]') || originalName.startsWith('[RECOVERED_LOSTDIR]')) {
        targetDir = this.recoverySDCardDir;
      } else {
        targetDir = this.recoveryDir;
      }
    }

    // 3. Human-readable filename with collision protection
    const sanitizedBase = ArchiveManager.sanitizeFilename(originalName);
    const ext = path.extname(sanitizedBase);
    const stem = ext ? sanitizedBase.substring(0, sanitizedBase.length - ext.length) : sanitizedBase;

    let targetPath = path.join(targetDir, sanitizedBase);

    if (fs.existsSync(targetPath)) {
      const existingHash = await this.computeSha256(targetPath);
      if (existingHash.toLowerCase() === actualHash.toLowerCase()) {
        // Exact identical duplicate file: discard staging copy
        fs.unlinkSync(stagingPath);
        return { targetPath, category, isDuplicate: true };
      }

      // Filename collision with DIFFERENT content: preserve both using short hash suffix!
      const shortHash = actualHash.substring(0, 8);
      const disambiguatedName = `${stem} (${shortHash})${ext}`;
      targetPath = path.join(targetDir, disambiguatedName);
    }

    // 4. Atomic move
    try {
      fs.renameSync(stagingPath, targetPath);
    } catch (err) {
      fs.copyFileSync(stagingPath, targetPath);
      fs.unlinkSync(stagingPath);
    }

    return { targetPath, category, isDuplicate: false };
  }
}

module.exports = { ArchiveManager };
