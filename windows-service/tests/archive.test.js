const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');

const { ArchiveManager } = require('../src/archive');
const { Database } = require('../src/db');
const { AppConfig } = require('../src/config');
const { BackupServer } = require('../src/server');

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'backup-test-'));
}

test('ArchiveManager: filename sanitization prevents path traversal and illegal chars', () => {
  assert.equal(ArchiveManager.sanitizeFilename('../../etc/passwd'), 'etc_passwd');
  assert.equal(ArchiveManager.sanitizeFilename('..\\..\\Windows\\System32\\cmd.exe'), 'Windows_System32_cmd.exe');
  assert.equal(ArchiveManager.sanitizeFilename('photo:1.jpg'), 'photo_1.jpg');
  assert.equal(ArchiveManager.sanitizeFilename('my video?.mp4'), 'my video_.mp4');
  assert.equal(ArchiveManager.sanitizeFilename('...'), 'unnamed_file');
  assert.equal(ArchiveManager.sanitizeFilename(''), 'unnamed_file');
});

test('ArchiveManager: chunked upload, human-browseable Photos directory, and SHA-256 verification', async () => {
  const tmp = makeTempDir();
  const archive = new ArchiveManager(tmp, 1024 * 1024);
  const stagingPath = archive.stagingPathForSession('test-session-1');

  const chunk1 = Buffer.from('Chunk 1: First 30 bytes of photo data. ');
  const chunk2 = Buffer.from('Chunk 2: Second 30 bytes of photo data.');
  const full = Buffer.concat([chunk1, chunk2]);
  const expectedHash = crypto.createHash('sha256').update(full).digest('hex');

  // Write chunk 1 at offset 0
  const len1 = archive.writeChunk(stagingPath, 0, chunk1);
  assert.equal(len1, chunk1.length);

  // Attempt write with gap (offset 100 > len1) -> must throw
  assert.throws(
    () => archive.writeChunk(stagingPath, 100, chunk2),
    /Chunk offset gap detected/
  );

  // Write chunk 2 at exact offset len1
  const len2 = archive.writeChunk(stagingPath, len1, chunk2);
  assert.equal(len2, full.length);

  // Verify hash
  const computedHash = await archive.computeSha256(stagingPath);
  assert.equal(computedHash, expectedHash);

  // Commit upload to human-browseable Photos/
  const { targetPath, category } = await archive.commitUpload(stagingPath, expectedHash, 'vacation.jpg', 'image/jpeg', false);
  assert.equal(category, 'Photos');
  assert.ok(fs.existsSync(targetPath));
  assert.ok(targetPath.includes('Photos'));
  assert.ok(!fs.existsSync(stagingPath), 'Staging file must be cleaned up after commit');

  fs.rmSync(tmp, { recursive: true, force: true });
});

test('ArchiveManager: tamper detection quarantines corrupt files', async () => {
  const tmp = makeTempDir();
  const archive = new ArchiveManager(tmp, 1024 * 1024);
  const stagingPath = archive.stagingPathForSession('corrupt-session');

  const pristine = Buffer.from('Original pristine photo data');
  const expectedHash = crypto.createHash('sha256').update(pristine).digest('hex');

  const tampered = Buffer.from('Tampered corrupt photo data');
  archive.writeChunk(stagingPath, 0, tampered);

  await assert.rejects(
    async () => {
      await archive.commitUpload(stagingPath, expectedHash, 'corrupt.jpg', 'image/jpeg', false);
    },
    /INTEGRITY VERIFICATION FAILED/
  );

  assert.ok(!fs.existsSync(stagingPath), 'Corrupted staging file must be moved');
  const quarantined = fs.readdirSync(archive.quarantineDir);
  assert.equal(quarantined.length, 1);
  assert.ok(quarantined[0].includes('mismatch'));

  fs.rmSync(tmp, { recursive: true, force: true });
});

test('ArchiveManager: deduplication and filename collision handling', async () => {
  const tmp = makeTempDir();
  const archive = new ArchiveManager(tmp, 1024 * 1024);

  const payloadA = Buffer.from('Image payload A');
  const hashA = crypto.createHash('sha256').update(payloadA).digest('hex');

  // First commit
  const s1 = archive.stagingPathForSession('sess-1');
  archive.writeChunk(s1, 0, payloadA);
  const res1 = await archive.commitUpload(s1, hashA, 'camera.jpg', 'image/jpeg', false);

  // Second commit: identical file, identical name -> deduplicated
  const s2 = archive.stagingPathForSession('sess-2');
  archive.writeChunk(s2, 0, payloadA);
  const res2 = await archive.commitUpload(s2, hashA, 'camera.jpg', 'image/jpeg', false);
  assert.ok(res2.isDuplicate);
  assert.equal(res1.targetPath, res2.targetPath);

  // Third commit: DIFFERENT content, SAME filename -> collision disambiguation, no overwrite!
  const payloadB = Buffer.from('Image payload B with different bytes');
  const hashB = crypto.createHash('sha256').update(payloadB).digest('hex');
  const s3 = archive.stagingPathForSession('sess-3');
  archive.writeChunk(s3, 0, payloadB);
  const res3 = await archive.commitUpload(s3, hashB, 'camera.jpg', 'image/jpeg', false);

  assert.notEqual(res1.targetPath, res3.targetPath);
  assert.ok(fs.existsSync(res1.targetPath));
  assert.ok(fs.existsSync(res3.targetPath));
  assert.equal(fs.readFileSync(res1.targetPath, 'utf8'), payloadA.toString());
  assert.equal(fs.readFileSync(res3.targetPath, 'utf8'), payloadB.toString());

  fs.rmSync(tmp, { recursive: true, force: true });
});

test('Database: device registry, recovery key, and revocation', () => {
  const tmp = makeTempDir();
  const dbPath = path.join(tmp, 'Metadata', 'archive_manifest.db');
  const archiveId = 'arch-test-123';
  const recoveryKey = 'RCV-TEST-1234-5678-ABCD';
  const recoveryKeyHash = crypto.createHash('sha256').update(recoveryKey).digest('hex');

  let db = new Database(dbPath, archiveId, recoveryKeyHash);

  // 1. Verify recovery key
  assert.ok(db.verifyRecoveryKey(recoveryKey));
  assert.ok(!db.verifyRecoveryKey('WRONG-KEY'));

  // 2. Register device
  const tokenHash = crypto.createHash('sha256').update('secret-tok-1').digest('hex');
  db.registerDevice('phone-1', 'Samsung A05s', tokenHash);
  assert.ok(db.verifyDeviceToken(tokenHash));

  // 3. Revoke device
  db.revokeDevice('phone-1');
  assert.ok(!db.verifyDeviceToken(tokenHash), 'Revoked device must be barred');

  db.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

test('BackupServer: end-to-end restore and replacement device recovery', async () => {
  const tmp = makeTempDir();
  const config = new AppConfig({
    archiveDir: path.join(tmp, 'Samsung Galaxy A05s Backup'),
    listenHost: '127.0.0.1',
    listenPort: 9878,
    pairingCode: 'TEST-CODE-42',
    minFreeSpaceBytes: 1024,
    chunkSizeBytes: 1024,
  });

  const archive = new ArchiveManager(config.archiveDir, config.minFreeSpaceBytes);
  const db = new Database(path.join(archive.metadataDir, 'archive_manifest.db'), config.archiveId, config.recoveryKeyHash);
  const server = new BackupServer(config, db, archive);

  await server.start();
  const baseUrl = `http://127.0.0.1:${config.listenPort}`;

  try {
    // 1. Pair original phone
    const pairRes = await fetch(`${baseUrl}/api/v1/auth/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'phone-original',
        deviceName: 'Samsung Galaxy A05s',
        pairingCode: 'TEST-CODE-42',
      }),
    });
    const pairData = await pairRes.json();
    const origToken = pairData.authToken;

    // 2. Upload a photo
    const photoBytes = Buffer.from('High resolution birthday photo');
    const photoHash = crypto.createHash('sha256').update(photoBytes).digest('hex');

    const initRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${origToken}` },
      body: JSON.stringify({
        originalName: 'family.jpg',
        mediaType: 'image/jpeg',
        totalBytes: photoBytes.length,
        contentHash: photoHash,
        isManualFile: false,
      }),
    });
    const initData = await initRes.json();

    await fetch(`${baseUrl}/api/v1/upload/chunk/${initData.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${origToken}`, 'X-Offset': '0', 'Content-Type': 'application/octet-stream' },
      body: photoBytes,
    });

    await fetch(`${baseUrl}/api/v1/upload/finalize/${initData.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${origToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedSha256: photoHash }),
    });

    // 3. FACTORY RESET / REPLACEMENT PHONE SCENARIO:
    // Old phone is gone. New phone authorizes using RECOVERY KEY!
    const recoverRes = await fetch(`${baseUrl}/api/v1/auth/recover`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'phone-new-replacement',
        deviceName: 'New Galaxy A05s',
        recoveryKey: config.recoveryKey,
      }),
    });
    assert.equal(recoverRes.status, 200);
    const recoverData = await recoverRes.json();
    assert.ok(recoverData.success);
    const newToken = recoverData.authToken;

    // 4. RESTORE: Query available files
    const restoreListRes = await fetch(`${baseUrl}/api/v1/restore/list`, {
      headers: { Authorization: `Bearer ${newToken}` },
    });
    assert.equal(restoreListRes.status, 200);
    const restoreList = await restoreListRes.json();
    assert.equal(restoreList.totalFiles, 1);
    assert.equal(restoreList.files[0].contentHash, photoHash);

    // 5. RESTORE: Stream file back to new phone
    const fileRes = await fetch(`${baseUrl}/api/v1/restore/file/${photoHash}`, {
      headers: { Authorization: `Bearer ${newToken}` },
    });
    assert.equal(fileRes.status, 200);
    const downloadedBuf = Buffer.from(await fileRes.arrayBuffer());
    assert.equal(downloadedBuf.toString(), photoBytes.toString());
    const downloadedHash = crypto.createHash('sha256').update(downloadedBuf).digest('hex');
    assert.equal(downloadedHash, photoHash);

    // 6. Revoke old phone
    const revokeRes = await fetch(`${baseUrl}/api/v1/admin/revoke-device`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${newToken}` },
      body: JSON.stringify({ deviceId: 'phone-original' }),
    });
    assert.equal(revokeRes.status, 200);

    // Verify old phone is barred
    const blockedRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${origToken}` },
      body: JSON.stringify({ contentHash: 'abc', originalName: 'blocked.jpg' }),
    });
    assert.equal(blockedRes.status, 401);
  } finally {
    await server.stop();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('ArchiveManager: multi-tiered recovery routing into structured subfolders', async () => {
  const tmp = makeTempDir();
  const archive = new ArchiveManager(tmp, 1024 * 1024);

  const testCases = [
    { name: '[RECOVERED_TRASH] photo1.jpg', expectedSubdir: 'Trash' },
    { name: '[RECOVERED_SAMSUNG_TRASH] photo2.jpg', expectedSubdir: 'Trash' },
    { name: '[RECOVERED_APP_CACHE] wa_img.jpg', expectedSubdir: 'AppCaches' },
    { name: '[RECOVERED_THUMBNAIL] thumb.jpg', expectedSubdir: 'Thumbnails' },
    { name: '[THUMBNAIL_REMNANT] preview.jpg', expectedSubdir: 'Thumbnails' },
    { name: '[RECOVERED_SDCARD] sd_file.jpg', expectedSubdir: 'SDCard' },
    { name: '[RECOVERED_LOSTDIR] chunk1.jpg', expectedSubdir: 'SDCard' },
  ];

  for (let i = 0; i < testCases.length; i++) {
    const { name, expectedSubdir } = testCases[i];
    const data = Buffer.from(`Recovery payload ${i}: ${name}`);
    const hash = crypto.createHash('sha256').update(data).digest('hex');
    const sPath = archive.stagingPathForSession(`recovery-sess-${i}`);
    archive.writeChunk(sPath, 0, data);
    const { targetPath, category } = await archive.commitUpload(sPath, hash, name, 'image/jpeg', false);
    assert.equal(category, 'Recovery');
    assert.ok(targetPath.includes(path.join('Recovery', expectedSubdir)), `Expected ${targetPath} to be in ${expectedSubdir}`);
    assert.ok(fs.existsSync(targetPath));
  }

  fs.rmSync(tmp, { recursive: true, force: true });
});

