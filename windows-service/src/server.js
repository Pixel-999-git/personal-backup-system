const http = require('node:http');
const crypto = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

function sendJson(res, statusCode, data) {
  const body = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Offset, X-Length, X-Chunk-Sha256',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  res.end(body);
}

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 10 * 1024 * 1024) {
        reject(new Error('Payload too large'));
      }
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (err) {
        reject(new Error('Invalid JSON payload'));
      }
    });
    req.on('error', (err) => reject(err));
  });
}

function readRawBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', (err) => reject(err));
  });
}

class BackupServer {
  constructor(config, db, archive) {
    this.config = config;
    this.db = db;
    this.archive = archive;
    this.updateDir = path.join(this.archive.rootDir, 'Metadata', 'updates');
    if (!fs.existsSync(this.updateDir)) {
      fs.mkdirSync(this.updateDir, { recursive: true });
    }

    this.server = http.createServer((req, res) => this.handleRequest(req, res));
  }

  verifyAuth(req) {
    const auth = req.headers['authorization'];
    if (!auth || !auth.startsWith('Bearer ')) {
      return false;
    }
    const token = auth.substring(7).trim();
    if (token === this.config.serverToken) {
      return true;
    }
    const tokenHash = hashToken(token);
    return this.db.verifyDeviceToken(tokenHash);
  }

  async handleRequest(req, res) {
    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = parsedUrl.pathname;
    const method = req.method.toUpperCase();

    // CORS preflight
    if (method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Offset, X-Length, X-Chunk-Sha256',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      });
      return res.end();
    }

    try {
      // 1. Health check
      if (method === 'GET' && pathname === '/api/v1/health') {
        const freeSpace = this.archive.checkFreeSpace();
        const stats = this.db.getStats();
        const capGb = this.config.maxStorageCapGb || 30;
        const maxStorageBytes = capGb * 1024 * 1024 * 1024;
        const usedBytes = stats.totalBytes || 0;
        const reportedFree = Math.max(0, Math.min(freeSpace, maxStorageBytes - usedBytes));
        return sendJson(res, 200, {
          status: 'RUNNING',
          serviceVersion: '1.0.0',
          archiveName: 'Samsung Galaxy A05s Cloud Backup',
          archiveDir: this.archive.rootDir,
          freeSpaceBytes: reportedFree,
          totalFiles: stats.totalFiles,
          totalBytesArchived: stats.totalBytes,
          isHealthy: freeSpace >= this.config.minFreeSpaceBytes,
        });
      }

      // 2. Initial Device Pairing
      if (method === 'POST' && pathname === '/api/v1/auth/pair') {
        const body = await parseJsonBody(req);
        if (!body.pairingCode || body.pairingCode.trim() !== this.config.pairingCode.trim()) {
          console.warn(`[Auth] Failed pairing attempt for device: ${body.deviceName}`);
          return sendJson(res, 403, { success: false, message: 'Invalid pairing code' });
        }

        const deviceId = body.deviceId || crypto.randomUUID();
        const deviceName = body.deviceName || 'Samsung Galaxy A05s';
        const newToken = `${crypto.randomUUID()}-${crypto.randomUUID()}`;
        const tokenHash = hashToken(newToken);

        this.db.registerDevice(deviceId, deviceName, tokenHash);
        console.log(`[Auth] Device successfully paired: ${deviceName} (${deviceId})`);

        return sendJson(res, 200, {
          success: true,
          message: 'Device paired successfully',
          archiveId: this.config.archiveId,
          recoveryKey: this.config.recoveryKey,
          authToken: newToken,
        });
      }

      // 3. New Phone / Replacement Device Recovery
      if (method === 'POST' && pathname === '/api/v1/auth/recover') {
        const body = await parseJsonBody(req);
        const recoveryKey = body.recoveryKey || '';

        if (!this.db.verifyRecoveryKey(recoveryKey)) {
          console.warn(`[Auth] Failed recovery attempt with key: ${recoveryKey}`);
          return sendJson(res, 403, { success: false, message: 'Invalid recovery key' });
        }

        const deviceId = body.deviceId || crypto.randomUUID();
        const deviceName = body.deviceName || 'Replacement Samsung Galaxy A05s';
        const newToken = `${crypto.randomUUID()}-${crypto.randomUUID()}`;
        const tokenHash = hashToken(newToken);

        this.db.registerDevice(deviceId, deviceName, tokenHash);
        this.db.logAudit('DEVICE_RECOVERED', `Authorized replacement device ${deviceName} (${deviceId}) via recovery key`);
        console.log(`[Auth] Replacement device authorized: ${deviceName} (${deviceId})`);

        return sendJson(res, 200, {
          success: true,
          message: 'Replacement device authorized successfully against existing archive',
          archiveId: this.config.archiveId,
          authToken: newToken,
        });
      }

      // 4. Device Revocation
      if (method === 'POST' && pathname === '/api/v1/admin/revoke-device') {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized admin request' });
        }
        const body = await parseJsonBody(req);
        const deviceId = body.deviceId;
        if (!deviceId) {
          return sendJson(res, 400, { error: 'Missing deviceId to revoke' });
        }
        this.db.revokeDevice(deviceId);
        return sendJson(res, 200, { success: true, message: `Device ${deviceId} revoked` });
      }

      // 5. Upload initialization (Resumability & Deduplication)
      if (method === 'POST' && pathname === '/api/v1/upload/init') {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const body = await parseJsonBody(req);
        const contentHash = (body.contentHash || '').toLowerCase();
        if (!contentHash) {
          return sendJson(res, 400, { error: 'Missing contentHash' });
        }

        // Deduplication check: Has this exact content hash already been verified in the archive?
        const alreadyArchived = this.db.checkFileExists(contentHash);
        if (alreadyArchived) {
          const category = this.archive.determineCategory(
            body.originalName || 'unknown',
            body.mediaType || 'application/octet-stream',
            body.isManualFile
          );

          this.db.recordFile({
            contentHash,
            originalName: body.originalName || 'unknown',
            mediaType: body.mediaType || 'application/octet-stream',
            sizeBytes: body.totalBytes || 0,
            relativePath: `${category}/${body.originalName}`,
            storageCategory: category,
            clientCreatedAt: body.clientCreatedAt,
            isManualFile: !!body.isManualFile,
          });

          return sendJson(res, 200, {
            sessionId: crypto.randomUUID(),
            isDuplicate: true,
            alreadyArchived: true,
            receivedBytes: body.totalBytes || 0,
            chunkSizeRecommended: this.config.chunkSizeBytes,
            message: 'File already verified in archive. No transfer needed (deduplicated).',
          });
        }

        // Resumable check: is there an active partial upload session for this hash?
        const existingSession = this.db.findActiveSessionByHash(contentHash);
        if (existingSession) {
          const stagingPath = existingSession.stagingPath;
          let actualStagingBytes = 0;
          if (fs.existsSync(stagingPath)) {
            actualStagingBytes = fs.statSync(stagingPath).size;
          }

          console.log(
            `[Upload] Resuming session ${existingSession.sessionId} for ${body.originalName} at ${actualStagingBytes} bytes`
          );

          return sendJson(res, 200, {
            sessionId: existingSession.sessionId,
            isDuplicate: false,
            alreadyArchived: false,
            receivedBytes: actualStagingBytes,
            chunkSizeRecommended: this.config.chunkSizeBytes,
            message: 'Resuming existing upload session',
          });
        }

        // Create fresh upload session
        const sessionId = crypto.randomUUID();
        const stagingPath = this.archive.stagingPathForSession(sessionId);

        this.db.createUploadSession({
          sessionId,
          contentHash,
          originalName: body.originalName || 'unknown',
          mediaType: body.mediaType || 'application/octet-stream',
          totalBytes: body.totalBytes || 0,
          stagingPath,
          isManualFile: !!body.isManualFile,
          clientCreatedAt: body.clientCreatedAt,
        });

        return sendJson(res, 200, {
          sessionId,
          isDuplicate: false,
          alreadyArchived: false,
          receivedBytes: 0,
          chunkSizeRecommended: this.config.chunkSizeBytes,
          message: 'New upload session initialized',
        });
      }

      // 6. Upload chunk (Resumable chunked receiver)
      if (method === 'POST' && pathname.startsWith('/api/v1/upload/chunk/')) {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const sessionId = pathname.substring('/api/v1/upload/chunk/'.length);
        const session = this.db.getSession(sessionId);
        if (!session) {
          return sendJson(res, 404, { error: 'Upload session not found' });
        }

        const offsetHeader = req.headers['x-offset'];
        if (offsetHeader === undefined) {
          return sendJson(res, 400, { error: 'Missing X-Offset header' });
        }
        const offset = Number(offsetHeader);

        const chunkBuffer = await readRawBody(req);
        const newLen = this.archive.writeChunk(session.stagingPath, offset, chunkBuffer);
        this.db.updateSessionProgress(sessionId, newLen);

        return sendJson(res, 200, {
          sessionId,
          offset,
          bytesWritten: chunkBuffer.length,
          totalReceived: newLen,
          complete: newLen >= session.totalBytes,
        });
      }

      // 7. Finalize upload & verify SHA-256
      if (method === 'POST' && pathname.startsWith('/api/v1/upload/finalize/')) {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const sessionId = pathname.substring('/api/v1/upload/finalize/'.length);
        const session = this.db.getSession(sessionId);
        if (!session) {
          return sendJson(res, 404, { error: 'Upload session not found' });
        }

        const body = await parseJsonBody(req);
        const expectedSha256 = (body.expectedSha256 || session.contentHash).toLowerCase();

        let commitResult;
        try {
          commitResult = await this.archive.commitUpload(
            session.stagingPath,
            expectedSha256,
            session.originalName,
            session.mediaType,
            session.isManualFile
          );
        } catch (commitErr) {
          if (commitErr.message.includes('INTEGRITY VERIFICATION FAILED')) {
            return sendJson(res, 422, { error: commitErr.message });
          }
          throw commitErr;
        }

        const relativePath = path.relative(this.archive.rootDir, commitResult.targetPath);

        this.db.recordFile({
          contentHash: expectedSha256,
          originalName: session.originalName,
          mediaType: session.mediaType,
          sizeBytes: session.totalBytes,
          relativePath,
          storageCategory: commitResult.category,
          clientCreatedAt: session.clientCreatedAt,
          isManualFile: session.isManualFile,
        });

        this.db.completeSession(sessionId);
        console.log(`[Upload] Finalized & verified: ${session.originalName} -> ${relativePath}`);

        return sendJson(res, 200, {
          success: true,
          verifiedSha256: expectedSha256,
          totalBytes: session.totalBytes,
          archivedPath: relativePath,
          message: 'File verified and archived successfully',
        });
      }

      // 8. Restore: List Files Available in Archive
      if (method === 'GET' && pathname === '/api/v1/restore/list') {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const files = this.db.getFilesForRestore();
        return sendJson(res, 200, {
          archiveId: this.config.archiveId,
          archiveName: 'Samsung Galaxy A05s Backup',
          totalFiles: files.length,
          files,
        });
      }

      // 9. Restore: Download Specific Archive File
      if (method === 'GET' && pathname.startsWith('/api/v1/restore/file/')) {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const hash = pathname.substring('/api/v1/restore/file/'.length).toLowerCase();
        const files = this.db.getFilesForRestore();
        const fileRecord = files.find((f) => f.contentHash.toLowerCase() === hash);

        if (!fileRecord) {
          return sendJson(res, 404, { error: 'File not found in archive manifest' });
        }

        const fullPath = path.join(this.archive.rootDir, fileRecord.relativePath);
        if (!fs.existsSync(fullPath)) {
          return sendJson(res, 404, { error: 'File payload missing on Windows disk' });
        }

        const stat = fs.statSync(fullPath);
        res.writeHead(200, {
          'Content-Type': fileRecord.mediaType || 'application/octet-stream',
          'Content-Length': stat.size,
          'X-Content-Sha256': fileRecord.contentHash,
          'X-Original-Name': encodeURIComponent(fileRecord.originalName),
        });
        return fs.createReadStream(fullPath).pipe(res);
      }

      // 10. Daily Reconciliation Manifest
      if (method === 'GET' && pathname === '/api/v1/reconcile/manifest') {
        if (!this.verifyAuth(req)) {
          return sendJson(res, 401, { error: 'Unauthorized device' });
        }

        const items = this.db.getManifestItems();
        return sendJson(res, 200, {
          serverTime: new Date().toISOString(),
          archiveId: this.config.archiveId,
          totalRecords: items.length,
          items,
        });
      }

      // 11. Secure Update Check
      if (method === 'GET' && pathname === '/api/v1/updates/check') {
        const manifestPath = path.join(this.updateDir, 'version.json');
        if (fs.existsSync(manifestPath)) {
          try {
            const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
            return sendJson(res, 200, manifest);
          } catch (_) {}
        }

        return sendJson(res, 200, {
          latestVersion: '1.0.0',
          versionCode: 1,
          apkSha256: '',
          downloadUrl: '/api/v1/updates/download',
          releaseNotes: 'Samsung Galaxy A05s Production Release with Restore Engine',
          updateAvailable: false,
        });
      }

      // 12. Secure Update Download
      if (method === 'GET' && pathname === '/api/v1/updates/download') {
        const apkPath = path.join(this.updateDir, 'app-release.apk');
        if (!fs.existsSync(apkPath)) {
          return sendJson(res, 404, { error: 'No update APK available on server' });
        }

        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': 'attachment; filename="app-release.apk"',
          'Content-Length': fs.statSync(apkPath).size,
        });
        return fs.createReadStream(apkPath).pipe(res);
      }

      return sendJson(res, 404, { error: `Endpoint not found: ${method} ${pathname}` });
    } catch (err) {
      console.error(`[Server] Error processing ${method} ${pathname}:`, err);
      return sendJson(res, 500, { error: err.message });
    }
  }

  start() {
    return new Promise((resolve) => {
      this.server.listen(this.config.listenPort, this.config.listenHost, () => {
        console.log(`[Server] Windows Backup Service listening at http://${this.config.listenHost}:${this.config.listenPort}`);
        console.log(`[Server] Permanent Archive: ${this.archive.rootDir}`);
        resolve();
      });
    });
  }

  stop() {
    return new Promise((resolve) => {
      this.server.close(() => {
        this.db.close();
        resolve();
      });
    });
  }
}

module.exports = { BackupServer };
