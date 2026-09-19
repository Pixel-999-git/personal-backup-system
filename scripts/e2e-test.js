const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');

const { ArchiveManager } = require('../windows-service/src/archive');
const { Database } = require('../windows-service/src/db');
const { AppConfig } = require('../windows-service/src/config');
const { BackupServer } = require('../windows-service/src/server');

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-test-'));
}

async function runE2E() {
  console.log('====================================================');
  console.log('  RUNNING END-TO-END ACCEPTANCE & RED-TEAM TESTS    ');
  console.log('====================================================');

  const tmp = makeTempDir();
  const config = new AppConfig({
    archiveDir: path.join(tmp, 'Samsung Galaxy A05s Backup'),
    listenHost: '127.0.0.1',
    listenPort: 9999,
    pairingCode: 'TEST-SECRET-1234',
    minFreeSpaceBytes: 1024 * 1024,
    chunkSizeBytes: 1024 * 64, // 64 KB chunks
  });

  const archive = new ArchiveManager(config.archiveDir, config.minFreeSpaceBytes);
  const db = new Database(path.join(archive.metadataDir, 'archive_manifest.db'), config.archiveId, config.recoveryKeyHash);
  const server = new BackupServer(config, db, archive);

  await server.start();
  const baseUrl = `http://127.0.0.1:${config.listenPort}`;

  let passed = 0;
  let failed = 0;

  function assert(condition, message) {
    if (condition) {
      console.log(`  [PASS] ${message}`);
      passed++;
    } else {
      console.error(`  [FAIL] ${message}`);
      failed++;
    }
  }

  try {
    // 1. Health check
    console.log('\n--- 1. Health & Storage Check ---');
    const healthRes = await fetch(`${baseUrl}/api/v1/health`);
    assert(healthRes.status === 200, 'Health endpoint responds 200');
    const health = await healthRes.json();
    assert(health.status === 'RUNNING', 'Service status is RUNNING');
    assert(health.archiveName === 'Samsung Galaxy A05s Backup', 'Archive name is Samsung Galaxy A05s Backup');
    assert(health.isHealthy === true, 'Service reports healthy');

    // 2. Authentication & Pairing
    console.log('\n--- 2. Device Pairing & Recovery Key Generation ---');
    // Bad pairing code
    const badPair = await fetch(`${baseUrl}/api/v1/auth/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'dev1', deviceName: 'Phone', pairingCode: 'WRONG' }),
    });
    assert(badPair.status === 403, 'Invalid pairing code rejected with 403');

    // Valid pairing
    const goodPair = await fetch(`${baseUrl}/api/v1/auth/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'dev1', deviceName: 'Samsung Galaxy A05s', pairingCode: 'TEST-SECRET-1234' }),
    });
    assert(goodPair.status === 200, 'Valid pairing code accepted with 200');
    const pairData = await goodPair.json();
    assert(!!pairData.authToken, 'Issued bearer auth token');
    assert(!!pairData.recoveryKey, 'Returned human-readable recovery key');
    const token = pairData.authToken;
    const recoveryKey = pairData.recoveryKey;

    // 3. Unauthorized access check
    console.log('\n--- 3. Red Team: Security & Unauthorized Access ---');
    const unauthInit = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer fake-token-123' },
      body: JSON.stringify({ contentHash: 'abc', originalName: 'a.jpg' }),
    });
    assert(unauthInit.status === 401 || unauthInit.status === 403, 'Bogus auth token rejected with 401/403');

    // 4. Multi-chunk Resumable Upload (Simulating Phone Network Drop at 50%)
    console.log('\n--- 4. Resumable Upload & Network Interruption Simulation ---');
    const videoData = crypto.randomBytes(256 * 1024); // 256 KB mock video
    const videoHash = crypto.createHash('sha256').update(videoData).digest('hex');

    const initRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        originalName: 'birthday_video.mp4',
        mediaType: 'video/mp4',
        totalBytes: videoData.length,
        contentHash: videoHash,
        clientCreatedAt: new Date().toISOString(),
        isManualFile: false,
      }),
    });
    assert(initRes.status === 200, 'Upload session initialized');
    const initData = await initRes.json();
    const sessionId = initData.sessionId;

    // Send first 50%
    const half = Math.floor(videoData.length / 2);
    const chunk1 = videoData.subarray(0, half);
    const chunk1Res = await fetch(`${baseUrl}/api/v1/upload/chunk/${sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'X-Offset': '0', 'Content-Type': 'application/octet-stream' },
      body: chunk1,
    });
    assert(chunk1Res.status === 200, 'Chunk 1 (50%) written successfully');

    // SIMULATE NETWORK DROP / CRASH: Re-init upload for same content
    const resumeInitRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        originalName: 'birthday_video.mp4',
        mediaType: 'video/mp4',
        totalBytes: videoData.length,
        contentHash: videoHash,
        clientCreatedAt: new Date().toISOString(),
        isManualFile: false,
      }),
    });
    const resumeInitData = await resumeInitRes.json();
    assert(resumeInitData.sessionId === sessionId, 'Server returned existing session ID on reconnect');
    assert(resumeInitData.receivedBytes === half, `Server remembered exact received byte offset (${half} bytes)`);

    // Send second 50%
    const chunk2 = videoData.subarray(half);
    const chunk2Res = await fetch(`${baseUrl}/api/v1/upload/chunk/${sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'X-Offset': String(half), 'Content-Type': 'application/octet-stream' },
      body: chunk2,
    });
    assert(chunk2Res.status === 200, 'Chunk 2 written successfully after resume');
    const chunk2Data = await chunk2Res.json();
    assert(chunk2Data.complete === true, 'Upload completed all bytes');

    // Finalize
    const finRes = await fetch(`${baseUrl}/api/v1/upload/finalize/${sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedSha256: videoHash }),
    });
    assert(finRes.status === 200, 'Finalize verified SHA-256 and committed atomically');

    // Verify file exists as ordinary browseable file in Videos/
    const videoOnDisk = path.join(archive.videosDir, 'birthday_video.mp4');
    assert(fs.existsSync(videoOnDisk), 'File saved as ordinary browseable file in Videos/ folder');

    // 5. Red Team: Data Tamper & Quarantine Defense
    console.log('\n--- 5. Red Team: Data Tamper & Quarantine Defense ---');
    const pristineData = Buffer.from('Legitimate data before transit');
    const correctHash = crypto.createHash('sha256').update(pristineData).digest('hex');

    const corruptInitRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        originalName: 'tampered.jpg',
        mediaType: 'image/jpeg',
        totalBytes: pristineData.length,
        contentHash: correctHash,
        isManualFile: false,
      }),
    });
    const corruptInit = await corruptInitRes.json();

    // Attacker modifies chunk bytes in transit:
    const tamperedBytes = Buffer.from('TAMPERED corrupted bytes payload');
    await fetch(`${baseUrl}/api/v1/upload/chunk/${corruptInit.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'X-Offset': '0', 'Content-Type': 'application/octet-stream' },
      body: tamperedBytes,
    });

    const corruptFin = await fetch(`${baseUrl}/api/v1/upload/finalize/${corruptInit.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedSha256: correctHash }),
    });
    assert(corruptFin.status === 422, 'Server rejected tampered file with 422 Unprocessable Entity');
    const quarantinedFiles = fs.readdirSync(archive.quarantineDir);
    assert(quarantinedFiles.length > 0, 'Tampered file quarantined to quarantine/ directory');

    // 6. Content-Addressed Deduplication Test
    console.log('\n--- 6. Deduplication Test ---');
    const dedupInit = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        originalName: 'renamed_video_copy.mp4',
        mediaType: 'video/mp4',
        totalBytes: videoData.length,
        contentHash: videoHash,
        isManualFile: false,
      }),
    });
    const dedupData = await dedupInit.json();
    assert(dedupData.isDuplicate === true, 'Detected duplicate content hash');
    assert(dedupData.alreadyArchived === true, 'Server confirmed zero-byte transfer required');

    // 7. Manual File Upload (PDF)
    console.log('\n--- 7. Manual File Upload (SAF / Documents) ---');
    const pdfData = Buffer.from('%PDF-1.4 Mock document content for legal document backup');
    const pdfHash = crypto.createHash('sha256').update(pdfData).digest('hex');

    const pdfInit = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        originalName: 'contract.pdf',
        mediaType: 'application/pdf',
        totalBytes: pdfData.length,
        contentHash: pdfHash,
        isManualFile: true,
      }),
    });
    const pdfSession = await pdfInit.json();

    await fetch(`${baseUrl}/api/v1/upload/chunk/${pdfSession.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'X-Offset': '0', 'Content-Type': 'application/octet-stream' },
      body: pdfData,
    });

    const pdfFin = await fetch(`${baseUrl}/api/v1/upload/finalize/${pdfSession.sessionId}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedSha256: pdfHash }),
    });
    assert(pdfFin.status === 200, 'Manual PDF file verified and archived to Files/ directory');
    assert(fs.existsSync(path.join(archive.filesDir, 'contract.pdf')), 'PDF browseable in Files/ folder');

    // 8. Daily Reconciliation Manifest
    console.log('\n--- 8. Daily Reconciliation Manifest ---');
    const reconRes = await fetch(`${baseUrl}/api/v1/reconcile/manifest`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const reconData = await reconRes.json();
    assert(reconData.totalRecords >= 2, `Reconciliation returned all verified archive records (${reconData.totalRecords} found)`);

    // 9. REPLACEMENT PHONE RESTORE & RECOVERY KEY AUTHORIZATION
    console.log('\n--- 9. Replacement Phone Restore via Recovery Key ---');
    const recoverRes = await fetch(`${baseUrl}/api/v1/auth/recover`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'dev-replacement-phone',
        deviceName: 'Replacement Galaxy A05s',
        recoveryKey,
      }),
    });
    assert(recoverRes.status === 200, 'Replacement phone authorized using recovery key');
    const recoverData = await recoverRes.json();
    const replacementToken = recoverData.authToken;

    // Query restore list
    const restoreListRes = await fetch(`${baseUrl}/api/v1/restore/list`, {
      headers: { Authorization: `Bearer ${replacementToken}` },
    });
    const restoreList = await restoreListRes.json();
    assert(restoreList.totalFiles >= 2, `Restore query returned ${restoreList.totalFiles} files for restoration`);

    // Download file for restore
    const restoreFileRes = await fetch(`${baseUrl}/api/v1/restore/file/${pdfHash}`, {
      headers: { Authorization: `Bearer ${replacementToken}` },
    });
    assert(restoreFileRes.status === 200, 'Downloaded archived file for restore');
    const restoredBuf = Buffer.from(await restoreFileRes.arrayBuffer());
    assert(restoredBuf.toString() === pdfData.toString(), 'Restored file bytes match 100%');

    // 10. Device Revocation
    console.log('\n--- 10. Device Revocation ---');
    const revokeRes = await fetch(`${baseUrl}/api/v1/admin/revoke-device`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${replacementToken}` },
      body: JSON.stringify({ deviceId: 'dev1' }),
    });
    assert(revokeRes.status === 200, 'Revoked old device ID');

    // Attempt upload with old revoked token -> must fail
    const revokedUpload = await fetch(`${baseUrl}/api/v1/upload/init`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ contentHash: 'abc', originalName: 'hack.jpg' }),
    });
    assert(revokedUpload.status === 401, 'Revoked device barred from archive');

    console.log('\n====================================================');
    console.log(`  E2E TEST RESULTS: ${passed} PASSED, ${failed} FAILED`);
    console.log('====================================================');
  } finally {
    await server.stop();
    fs.rmSync(tmp, { recursive: true, force: true });
  }

  if (failed > 0) {
    process.exit(1);
  }
}

runE2E().catch((err) => {
  console.error('[E2E Error]', err);
  process.exit(1);
});
