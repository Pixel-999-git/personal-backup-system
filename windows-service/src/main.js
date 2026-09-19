const path = require('node:path');
const fs = require('node:fs');
const { AppConfig } = require('./config');
const { Database } = require('./db');
const { ArchiveManager } = require('./archive');
const { BackupServer } = require('./server');

async function main() {
  const args = process.argv.slice(2);
  const command = args[0] || 'run';

  const configPath = process.env.BACKUP_CONFIG || path.resolve(__dirname, '..', 'config.json');
  const config = AppConfig.loadOrCreate(configPath);

  const archive = new ArchiveManager(config.archiveDir, config.minFreeSpaceBytes);
  const dbPath = path.join(archive.metadataDir, 'archive_manifest.db');
  const db = new Database(dbPath, config.archiveId, config.recoveryKeyHash);

  if (command === 'run') {
    console.log('====================================================');
    console.log('  Windows Backup Service — Samsung Galaxy A05s      ');
    console.log('====================================================');
    console.log(`Archive Name       : Samsung Galaxy A05s Backup`);
    console.log(`Archive Directory  : ${archive.rootDir}`);
    console.log(`Archive ID         : ${config.archiveId}`);
    console.log(`Listening Host     : ${config.listenHost}`);
    console.log(`Listening Port     : ${config.listenPort}`);
    console.log(`Pairing Code       : ${config.pairingCode}`);
    console.log(`Recovery Key       : ${config.recoveryKey}`);
    console.log('====================================================');

    const server = new BackupServer(config, db, archive);
    await server.start();

    const shutdown = async () => {
      console.log('\n[Server] Shutting down gracefully...');
      await server.stop();
      console.log('[Server] Service stopped.');
      process.exit(0);
    };

    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);
  } else if (command === 'verify') {
    console.log('====================================================');
    console.log('  Offline Archive Audit & Cryptographic Verification');
    console.log('====================================================');

    const items = db.getManifestItems();
    console.log(`Auditing ${items.length} verified records in database...`);

    let okCount = 0;
    let missingCount = 0;
    let corruptedCount = 0;

    for (const item of items) {
      const targetPath = path.join(archive.rootDir, item.relativePath);

      if (!fs.existsSync(targetPath)) {
        console.error(`[MISSING] ${item.originalName} (${item.relativePath})`);
        missingCount++;
      } else {
        const actualHash = await archive.computeSha256(targetPath);
        if (actualHash.toLowerCase() === item.contentHash.toLowerCase()) {
          okCount++;
        } else {
          console.error(`[CORRUPT] ${targetPath} (Expected: ${item.contentHash}, Actual: ${actualHash})`);
          corruptedCount++;
        }
      }
    }

    console.log('----------------------------------------------------');
    console.log(`Verification Complete:`);
    console.log(`  OK        : ${okCount}`);
    console.log(`  Missing   : ${missingCount}`);
    console.log(`  Corrupted : ${corruptedCount}`);
    console.log('----------------------------------------------------');
    db.close();
    process.exit(corruptedCount > 0 || missingCount > 0 ? 1 : 0);
  } else if (command === 'list-devices') {
    console.log('====================================================');
    console.log('  Authorized Device Registry                        ');
    console.log('====================================================');
    const devices = db.listDevices();
    if (devices.length === 0) {
      console.log('No devices paired yet.');
    } else {
      for (const d of devices) {
        console.log(`Device ID    : ${d.deviceId}`);
        console.log(`Device Name  : ${d.deviceName}`);
        console.log(`Status       : ${d.status}`);
        console.log(`Registered   : ${d.registeredAt}`);
        console.log(`Last Seen    : ${d.lastSeenAt}`);
        console.log('----------------------------------------------------');
      }
    }
    db.close();
  } else if (command === 'revoke') {
    const deviceId = args[1];
    if (!deviceId) {
      console.error('Usage: node src/main.js revoke <deviceId>');
      db.close();
      process.exit(1);
    }
    db.revokeDevice(deviceId);
    console.log(`[Success] Device ${deviceId} revoked. It can no longer access this archive.`);
    db.close();
  } else if (command === 'show-recovery-key') {
    console.log('====================================================');
    console.log(`Archive ID   : ${config.archiveId}`);
    console.log(`Recovery Key : ${config.recoveryKey}`);
    console.log('====================================================');
    console.log('Use this Recovery Key when setting up a replacement or factory-reset phone.');
    db.close();
  } else {
    console.log(`Usage: node src/main.js [run|verify|list-devices|revoke <deviceId>|show-recovery-key]`);
    db.close();
    process.exit(1);
  }
}

main().catch((err) => {
  console.error('[Fatal]', err);
  process.exit(1);
});
