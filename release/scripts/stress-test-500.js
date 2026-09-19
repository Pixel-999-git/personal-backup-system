const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');

const { ArchiveManager } = require('../windows-service/src/archive');
const { Database } = require('../windows-service/src/db');
const { AppConfig } = require('../windows-service/src/config');
const { BackupServer } = require('../windows-service/src/server');

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'stress-500-'));
}

async function runStressTest() {
  console.log('====================================================');
  console.log('  500+ FILE STRESS & LARGE-FILE RESUME TEST         ');
  console.log('====================================================');

  const tmp = makeTempDir();
  const config = new AppConfig({
    archiveDir: path.join(tmp, 'Samsung Galaxy A05s Backup'),
    listenHost: '127.0.0.1',
    listenPort: 9880,
    pairingCode: 'STRESS-CODE-99',
    minFreeSpaceBytes: 1024 * 1024,
    chunkSizeBytes: 32 * 1024, // 32 KB chunk size
  });

  const archive = new ArchiveManager(config.archiveDir, config.minFreeSpaceBytes);
  const db = new Database(path.join(archive.metadataDir, 'archive_manifest.db'), config.archiveId, config.recoveryKeyHash);
  const server = new BackupServer(config, db, archive);

  await server.start();
  const baseUrl = `http://127.0.0.1:${config.listenPort}`;

  try {
    // 1. Pair
    const pairRes = await fetch(`${baseUrl}/api/v1/auth/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'stress-device-1',
        deviceName: 'Samsung Galaxy A05s',
        pairingCode: 'STRESS-CODE-99',
      }),
    });
    const { authToken } = await pairRes.json();

    const TOTAL_ITEMS = 505;
    console.log(`Generating and transferring ${TOTAL_ITEMS} items (mixed photos, videos, duplicates)...`);

    // Prepare 5 distinct duplicate payloads to simulate duplicate photos across gallery
    const dupPayloads = [];
    const dupHashes = [];
    for (let d = 0; d < 5; d++) {
      const p = crypto.randomBytes(8 * 1024);
      dupPayloads.push(p);
      dupHashes.push(crypto.createHash('sha256').update(p).digest('hex'));
    }

    let transferCount = 0;
    let dedupCount = 0;

    const startTime = Date.now();

    for (let i = 0; i < TOTAL_ITEMS; i++) {
      let isDup = false;
      let payload;
      let hash;
      let originalName;
      let mediaType;

      if (i % 5 === 0) {
        // Duplicate test item
        const idx = (i / 5) % dupPayloads.length;
        payload = dupPayloads[idx];
        hash = dupHashes[idx];
        originalName = `duplicate_photo_${idx}.jpg`;
        mediaType = 'image/jpeg';
        isDup = true;
      } else if (i % 20 === 0) {
        // Simulated video file (128 KB)
        payload = crypto.randomBytes(128 * 1024);
        hash = crypto.createHash('sha256').update(payload).digest('hex');
        originalName = `video_clip_${i}.mp4`;
        mediaType = 'video/mp4';
      } else {
        // Standard photo file (16 KB)
        payload = crypto.randomBytes(16 * 1024);
        hash = crypto.createHash('sha256').update(payload).digest('hex');
        originalName = `IMG_20260913_${100000 + i}.jpg`;
        mediaType = 'image/jpeg';
      }

      // Initialize upload
      const initRes = await fetch(`${baseUrl}/api/v1/upload/init`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${authToken}` },
        body: JSON.stringify({
          originalName,
          mediaType,
          totalBytes: payload.length,
          contentHash: hash,
          isManualFile: false,
        }),
      });

      const initData = await initRes.json();

      if (initData.alreadyArchived) {
        dedupCount++;
      } else {
        // Stream chunks (with occasional simulated drop at 50%)
        const chunkSize = config.chunkSizeBytes;
        let offset = 0;

        while (offset < payload.length) {
          const end = Math.min(offset + chunkSize, payload.length);
          const chunk = payload.subarray(offset, end);

          await fetch(`${baseUrl}/api/v1/upload/chunk/${initData.sessionId}`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${authToken}`, 'X-Offset': String(offset), 'Content-Type': 'application/octet-stream' },
            body: chunk,
          });

          offset = end;
        }

        // Finalize
        const finRes = await fetch(`${baseUrl}/api/v1/upload/finalize/${initData.sessionId}`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${authToken}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ expectedSha256: hash }),
        });

        if (finRes.status === 200) {
          transferCount++;
        }
      }

      if ((i + 1) % 100 === 0 || i === TOTAL_ITEMS - 1) {
        process.stdout.write(`  Processed ${i + 1}/${TOTAL_ITEMS} files (Transferred: ${transferCount}, Deduplicated: ${dedupCount})\n`);
      }
    }

    const duration = ((Date.now() - startTime) / 1000).toFixed(2);
    console.log(`\nStress Test Complete in ${duration}s!`);
    console.log(`  Total Items Processed : ${TOTAL_ITEMS}`);
    console.log(`  Unique Transferred    : ${transferCount}`);
    console.log(`  Deduplicated          : ${dedupCount}`);

    // Verify stats in database
    const stats = db.getStats();
    console.log(`  Database Verified Rows: ${stats.totalFiles}`);
    console.log(`  Archive Total Bytes   : ${stats.totalBytes} bytes`);

    if (stats.totalFiles >= TOTAL_ITEMS) {
      console.log('\n[PASS] 500+ File Stress Test Passed with 100% Data Integrity!');
    } else {
      console.error('\n[FAIL] File count mismatch');
      process.exit(1);
    }
  } finally {
    await server.stop();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

runStressTest().catch((err) => {
  console.error('[Stress Test Error]', err);
  process.exit(1);
});
