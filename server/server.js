'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT) || 8080;
// Bind to localhost by default; expose it through an HTTPS reverse proxy (e.g. Caddy).
const HOST = process.env.HOST || '127.0.0.1';
const SENDER_TOKEN = process.env.SENDER_TOKEN || '';
const RECEIVER_TOKEN = process.env.RECEIVER_TOKEN || '';
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data.json');
const BACKUP_DIR = process.env.BACKUP_DIR || path.join(path.dirname(DATA_FILE), 'backups');
const BACKUPS_TO_KEEP = 14;
const MAX_CLICKS_PER_SEND = 100;
const MAX_BODY_BYTES = 1024;

if (SENDER_TOKEN.length < 16 || RECEIVER_TOKEN.length < 16 || SENDER_TOKEN === RECEIVER_TOKEN) {
  console.error('Set SENDER_TOKEN and RECEIVER_TOKEN (different, at least 16 characters each).');
  process.exit(1);
}

function toCount(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

// Both totals only ever grow, so phones can safely re-send them and restore a lost server.
function parseState(data) {
  const totalReceived = toCount(data.totalReceived);
  const totalUsed = Number.isSafeInteger(data.totalUsed)
    ? toCount(data.totalUsed)
    : Math.max(0, totalReceived - toCount(data.available));
  return { totalReceived, totalUsed };
}

function listBackups() {
  try {
    return fs.readdirSync(BACKUP_DIR)
      .filter((name) => /^data-\d{4}-\d{2}-\d{2}\.json$/.test(name))
      .sort()
      .map((name) => path.join(BACKUP_DIR, name));
  } catch {
    return [];
  }
}

function loadState() {
  for (const file of [DATA_FILE, ...listBackups().reverse()]) {
    try {
      const loaded = parseState(JSON.parse(fs.readFileSync(file, 'utf8')));
      if (file !== DATA_FILE) console.warn(`Restored state from backup ${file}`);
      return loaded;
    } catch {
      // Missing or corrupt; try the next newest copy.
    }
  }
  return { totalReceived: 0, totalUsed: 0 };
}

const state = loadState();

function publicState() {
  return { ...state, available: Math.max(0, state.totalReceived - state.totalUsed) };
}

function saveState() {
  const tmp = `${DATA_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, DATA_FILE);
  try {
    fs.mkdirSync(BACKUP_DIR, { recursive: true });
    const today = new Date().toISOString().slice(0, 10);
    fs.copyFileSync(DATA_FILE, path.join(BACKUP_DIR, `data-${today}.json`));
    for (const old of listBackups().slice(0, -BACKUPS_TO_KEEP)) fs.unlinkSync(old);
  } catch (err) {
    console.error('Backup failed:', err.message);
  }
}

function tokenMatches(given, expected) {
  const a = crypto.createHash('sha256').update(given).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function roleOf(req) {
  const match = /^Bearer (\S+)$/.exec(req.headers.authorization || '');
  if (!match) return null;
  if (tokenMatches(match[1], SENDER_TOKEN)) return 'sender';
  if (tokenMatches(match[1], RECEIVER_TOKEN)) return 'receiver';
  return null;
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('Body too large'));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {});
      } catch {
        reject(new Error('Invalid JSON'));
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const role = roleOf(req);
  if (!role) return sendJson(res, 401, { error: 'Invalid secret code' });

  try {
    if (req.method === 'GET' && req.url === '/api/state') {
      return sendJson(res, 200, { ...publicState(), role });
    }

    // Each phone sends the total it owns (sender: received, receiver: used); the server keeps the max.
    if (req.method === 'POST' && req.url === '/api/sync') {
      const { total } = await readJson(req);
      if (!Number.isSafeInteger(total) || total < 0) return sendJson(res, 400, { error: 'Invalid total' });
      const key = role === 'sender' ? 'totalReceived' : 'totalUsed';
      if (total > state[key]) {
        state[key] = total;
        saveState();
        broadcast();
      }
      return sendJson(res, 200, { ...publicState(), role });
    }

    // /api/click and /api/use are kept for app versions older than the sync system.
    if (req.method === 'POST' && req.url === '/api/click') {
      if (role !== 'sender') return sendJson(res, 403, { error: 'Only the sender can send clicks' });
      const { amount } = await readJson(req);
      if (!Number.isInteger(amount) || amount < 1 || amount > MAX_CLICKS_PER_SEND) {
        return sendJson(res, 400, { error: `Amount must be 1-${MAX_CLICKS_PER_SEND}` });
      }
      state.totalReceived += amount;
      saveState();
      broadcast();
      return sendJson(res, 200, { ...publicState(), role });
    }

    if (req.method === 'POST' && req.url === '/api/use') {
      if (role !== 'receiver') return sendJson(res, 403, { error: 'Only the receiver can use clicks' });
      if (publicState().available === 0) return sendJson(res, 409, { error: 'No clicks left' });
      state.totalUsed += 1;
      saveState();
      broadcast();
      return sendJson(res, 200, { ...publicState(), role });
    }

    return sendJson(res, 404, { error: 'Not found' });
  } catch {
    return sendJson(res, 400, { error: 'Bad request' });
  }
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 });

function broadcast() {
  const message = JSON.stringify(publicState());
  for (const client of wss.clients) {
    if (client.readyState === client.OPEN) client.send(message);
  }
}

server.on('upgrade', (req, socket, head) => {
  if (req.url !== '/ws' || !roleOf(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.send(JSON.stringify(publicState()));
  });
});

// Drop dead connections so phones that lost signal reconnect cleanly.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);

server.listen(PORT, HOST, () => {
  console.log(`Puppy Clicker server listening on http://${HOST}:${PORT}`);
});
