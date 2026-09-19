const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function generateRecoveryKey() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // Avoid ambiguous 0/O, 1/I
  const parts = [];
  for (let p = 0; p < 4; p++) {
    let block = '';
    const bytes = crypto.randomBytes(4);
    for (let i = 0; i < 4; i++) {
      block += chars[bytes[i] % chars.length];
    }
    parts.push(block);
  }
  return `RCV-${parts.join('-')}`;
}

class AppConfig {
  constructor(options = {}) {
    const defaultArchive = path.resolve(__dirname, '..', 'Samsung Galaxy A05s Backup');
    this.archiveDir = options.archiveDir || defaultArchive;
    this.listenHost = options.listenHost || '0.0.0.0';
    this.listenPort = options.listenPort || 8976;
    this.pairingCode = options.pairingCode || 'BACKUP-7749';
    this.minFreeSpaceBytes = options.minFreeSpaceBytes || (1024 * 1024 * 1024); // 1 GB floor
    this.chunkSizeBytes = options.chunkSizeBytes || (2 * 1024 * 1024); // 2 MB recommended
    this.serverToken = options.serverToken || crypto.randomUUID();
    this.archiveId = options.archiveId || crypto.randomUUID();

    // Human-readable Recovery Key for authorizing replacement devices
    this.recoveryKey = options.recoveryKey || generateRecoveryKey();
    this.recoveryKeyHash = crypto.createHash('sha256').update(this.recoveryKey.trim().toUpperCase()).digest('hex');
  }

  static loadOrCreate(configFilePath) {
    const resolvedPath = path.resolve(configFilePath || 'config.json');
    if (fs.existsSync(resolvedPath)) {
      try {
        const raw = fs.readFileSync(resolvedPath, 'utf8');
        const parsed = JSON.parse(raw);
        const config = new AppConfig(parsed);
        // Ensure archive directory conforms to required name if default was used
        if (config.archiveDir.endsWith('Archive')) {
          config.archiveDir = path.join(path.dirname(config.archiveDir), 'Samsung Galaxy A05s Backup');
          fs.writeFileSync(resolvedPath, JSON.stringify(config, null, 2), 'utf8');
        }
        return config;
      } catch (err) {
        console.warn(`[Config] Failed to parse ${resolvedPath}, falling back to defaults:`, err.message);
      }
    }

    const config = new AppConfig();
    const parent = path.dirname(resolvedPath);
    if (!fs.existsSync(parent)) {
      fs.mkdirSync(parent, { recursive: true });
    }
    fs.writeFileSync(resolvedPath, JSON.stringify(config, null, 2), 'utf8');
    console.log(`[Config] Created configuration at ${resolvedPath}`);
    console.log(`[Config] Permanent Archive Directory: ${config.archiveDir}`);
    console.log(`[Config] Recovery Key for New Phone Restore: ${config.recoveryKey}`);
    return config;
  }
}

module.exports = { AppConfig, generateRecoveryKey };
