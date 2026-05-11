'use strict';
const http      = require('http');
const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const { validateToken } = require('./auth');
const ptyManager  = require('./ptyManager');
const repoManager = require('./repoManager');

// ── CORS helper ────────────────────────────────────────────────────────────────
function setCors(res) {
  res.setHeader('Access-Control-Allow-Origin',  '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

// ── JSON response helpers ──────────────────────────────────────────────────────
function jsonOk(res, data)  { res.writeHead(200, {'Content-Type':'application/json'}); res.end(JSON.stringify(data)); }
function jsonErr(res, code, msg) { res.writeHead(code, {'Content-Type':'application/json'}); res.end(JSON.stringify({error:msg})); }

// ── HTTP request handler ───────────────────────────────────────────────────────
const path = require('path');
const fs   = require('fs');
const HTML_PATH = path.join(__dirname, '..', '..', 'web-terminal.html');

async function handleHttp(req, res, authToken) {
  setCors(res);
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // ── GET / ── serve the web terminal UI (no auth required)
  if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
    try {
      const html = fs.readFileSync(HTML_PATH);
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
    } catch { res.writeHead(404); res.end('web-terminal.html not found'); }
    return;
  }

  // Auth via Bearer token
  const bearer = (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '').trim();
  if (!validateToken(bearer, authToken)) { jsonErr(res, 401, 'Unauthorized'); return; }

  const url = req.url.split('?')[0];

  // ── GET /api/repos ── local repos + GitHub if token is saved
  if (req.method === 'GET' && url === '/api/repos') {
    try {
      const ghToken = (process.env.GITHUB_TOKEN || '').trim();
      let repos     = repoManager.scanLocalRepos();
      if (ghToken) {
        try {
          const ghRepos  = await repoManager.fetchGithubRepos(ghToken);
          const clonedSet = new Set(repos.map(r => r.name));
          for (const r of ghRepos) { if (clonedSet.has(r.name)) r.cloned = true; }
          repos = ghRepos;
        } catch (e) {
          console.warn('[repos] GitHub fetch failed:', e.message);
        }
      }
      jsonOk(res, { repos, reposDir: repoManager.REPOS_DIR, hasGithubToken: !!ghToken });
    } catch (e) { jsonErr(res, 500, e.message); }
    return;
  }

  // ── GET /api/clone-status ── progress of background clone-all
  if (req.method === 'GET' && url === '/api/clone-status') {
    jsonOk(res, repoManager.cloneState);
    return;
  }

  // Remaining routes need a body
  const body = await readBody(req);
  let json = {};
  try { json = JSON.parse(body); } catch {}

  // ── POST /api/github-token ── save token to .env and trigger clone-all
  if (req.method === 'POST' && url === '/api/github-token') {
    if (!json.token) { jsonErr(res, 400, 'token obrigatório'); return; }
    try {
      const repos = await repoManager.fetchGithubRepos(json.token);
      repoManager.updateEnv('GITHUB_TOKEN', json.token);
      const clonedSet = new Set(repoManager.scanLocalRepos().map(r => r.name));
      for (const r of repos) { if (clonedSet.has(r.name)) r.cloned = true; }
      // start clone-all in background
      repoManager.cloneAll(repos);
      jsonOk(res, { repos, reposDir: repoManager.REPOS_DIR, hasGithubToken: true });
    } catch (e) { jsonErr(res, 400, e.message); }
    return;
  }

  // ── POST /api/open-folder ── open folder in Explorer on server machine
  if (req.method === 'POST' && url === '/api/open-folder') {
    if (!json.path) { jsonErr(res, 400, 'path obrigatório'); return; }
    try {
      const { spawn } = require('child_process');
      spawn('explorer.exe', [json.path], { detached: true, stdio: 'ignore' }).unref();
      jsonOk(res, { ok: true });
    } catch (e) { jsonErr(res, 500, e.message); }
    return;
  }

  // ── POST /api/remove-all-repos ── delete all local cloned repos
  if (req.method === 'POST' && url === '/api/remove-all-repos') {
    try {
      const fs             = require('fs');
      const { execSync }   = require('child_process');
      const repos  = repoManager.scanLocalRepos();
      const failed = [];
      for (const r of repos) {
        try { fs.rmSync(r.path, { recursive: true, force: true }); } catch {}
        if (fs.existsSync(r.path) && process.platform === 'win32') {
          try { execSync(`rmdir /s /q "${r.path}"`, { stdio: 'pipe', timeout: 8000 }); } catch {}
        }
        if (fs.existsSync(r.path)) failed.push(r.name);
      }
      jsonOk(res, { removed: repos.length - failed.length, failed });
    } catch (e) { jsonErr(res, 500, e.message); }
    return;
  }

  // ── POST /api/clone-all ── clone all non-cloned repos in background
  if (req.method === 'POST' && url === '/api/clone-all') {
    try {
      const ghToken = (process.env.GITHUB_TOKEN || '').trim();
      if (!ghToken) { jsonErr(res, 400, 'Nenhum token GitHub configurado'); return; }
      const repos = await repoManager.fetchGithubRepos(ghToken);
      const clonedSet = new Set(repoManager.scanLocalRepos().map(r => r.name));
      for (const r of repos) { if (clonedSet.has(r.name)) r.cloned = true; }
      const total = repos.filter(r => !r.cloned).length;
      repoManager.cloneAll(repos); // background, no await
      jsonOk(res, { started: true, total });
    } catch (e) { jsonErr(res, 500, e.message); }
    return;
  }

  res.writeHead(404); res.end('Not found');
}

function readBody(req) {
  return new Promise(resolve => {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => resolve(body));
  });
}

// ── WebSocket + HTTP server factory ───────────────────────────────────────────
function createServer(port, authToken) {
  const httpServer = http.createServer((req, res) => {
    handleHttp(req, res, authToken).catch(e => {
      console.error('[HTTP]', e.message);
      try { res.writeHead(500); res.end('Internal error'); } catch {}
    });
  });

  const wss = new WebSocket.Server({
    server: httpServer,
    perMessageDeflate: false,
    maxPayload: 10 * 1024 * 1024,
  });

  wss.on('connection', (ws, req) => {
    const clientId = uuidv4().slice(0, 8);
    const ip = req.socket.remoteAddress;
    let authed        = false;
    let sessionId     = null;
    let currentSession = null;
    let dataListener  = null;
    let exitListener  = null;

    console.log(`[+] ${clientId} from ${ip}`);

    const send = (obj) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
    };

    const attachSession = (session) => {
      if (dataListener && currentSession) {
        currentSession.removeListener('data', dataListener);
        currentSession.removeListener('exit', exitListener);
      }
      currentSession = session;
      dataListener = (data) => send({ type: 'output', data });
      exitListener = (code) => send({ type: 'session_exit', code, sessionId: session.id });
      session.on('data', dataListener);
      session.on('exit', exitListener);
    };

    const authTimeout = setTimeout(() => {
      if (!authed) { send({ type: 'error', message: 'Auth timeout' }); ws.close(4001, 'timeout'); }
    }, 15000);

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString('utf8')); }
      catch { send({ type: 'error', message: 'Invalid JSON' }); return; }

      if (!authed) {
        if (msg.type !== 'auth') { ws.close(4001, 'Not authenticated'); return; }
        if (!validateToken(msg.token, authToken)) {
          send({ type: 'auth_failed', message: 'Invalid token' });
          console.log(`[✗] ${clientId} bad token`);
          ws.close(4003, 'Invalid token');
          return;
        }
        clearTimeout(authTimeout);
        authed = true;
        const reqId  = msg.sessionId;
        let session  = reqId ? ptyManager.get(reqId) : null;
        if (!session) {
          sessionId = uuidv4();
          try { session = ptyManager.create(sessionId, { cols: msg.cols || 120, rows: msg.rows || 40 }); }
          catch (e) { send({ type: 'error', message: e.message }); ws.close(); return; }
        } else {
          sessionId = session.id;
        }
        attachSession(session);
        send({ type: 'auth_success', sessionId, sessions: ptyManager.list() });
        console.log(`[✓] ${clientId} auth ok — session ${sessionId.slice(0, 8)}`);
        return;
      }

      switch (msg.type) {
        case 'input':
          if (!currentSession?.alive) { send({ type: 'error', message: 'No active session' }); break; }
          currentSession.write(msg.data ?? '');
          break;
        case 'resize':
          currentSession?.resize(msg.cols ?? 120, msg.rows ?? 40);
          break;
        case 'new_session': {
          const newId = uuidv4();
          let s;
          try { s = ptyManager.create(newId, { cols: msg.cols ?? 120, rows: msg.rows ?? 40 }); }
          catch (e) { send({ type: 'error', message: e.message }); break; }
          sessionId = newId;
          attachSession(s);
          send({ type: 'session_created', sessionId: newId, sessions: ptyManager.list() });
          break;
        }
        case 'switch_session': {
          const t = ptyManager.get(msg.sessionId);
          if (!t) { send({ type: 'error', message: 'Session not found' }); break; }
          sessionId = t.id;
          attachSession(t);
          send({ type: 'session_switched', sessionId, sessions: ptyManager.list() });
          break;
        }
        case 'kill_session': {
          const tid = msg.sessionId ?? sessionId;
          ptyManager.destroy(tid);
          send({ type: 'session_killed', sessionId: tid, sessions: ptyManager.list() });
          break;
        }
        case 'list_sessions':
          send({ type: 'sessions', list: ptyManager.list() });
          break;
        case 'ping':
          send({ type: 'pong', ts: Date.now() });
          break;
        default:
          send({ type: 'error', message: `Unknown: ${msg.type}` });
      }
    });

    ws.on('close', (code) => {
      clearTimeout(authTimeout);
      if (dataListener && currentSession) {
        currentSession.removeListener('data', dataListener);
        currentSession.removeListener('exit', exitListener);
      }
      console.log(`[-] ${clientId} closed (${code})`);
    });

    ws.on('error', (e) => console.error(`[!] ${clientId}:`, e.message));
  });

  wss.on('error', (e) => console.error('[Server] Error:', e.message));

  httpServer.listen(port);
  return httpServer;
}

module.exports = { createServer };
