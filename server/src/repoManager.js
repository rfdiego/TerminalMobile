'use strict';
const fs      = require('fs');
const path    = require('path');
const os      = require('os');
const https   = require('https');
const { execSync, spawn } = require('child_process');

const ENV_PATH = path.join(__dirname, '..', '.env');

const REPOS_DIR = (() => {
  const d = (process.env.REPOS_DIR || '').trim();
  if (d) return d.replace(/^~/, os.homedir());
  return path.join(os.homedir(), 'projects');
})();

// ── .env helpers ───────────────────────────────────────────────────────────────
function updateEnv(key, value) {
  let content = '';
  try { content = fs.readFileSync(ENV_PATH, 'utf8'); } catch {}
  const lines = content.split('\n');
  const idx   = lines.findIndex(l => l.startsWith(key + '='));
  if (idx >= 0) lines[idx] = key + '=' + value;
  else lines.push(key + '=' + value);
  fs.writeFileSync(ENV_PATH, lines.join('\n'), 'utf8');
  process.env[key] = value;
}

// ── Local scanning ─────────────────────────────────────────────────────────────
function ensureDir(d) {
  if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
}

const EXT_MAP = {
  '.js':'JavaScript','.ts':'TypeScript','.jsx':'React','.tsx':'React/TS',
  '.kt':'Kotlin','.java':'Java','.py':'Python','.go':'Go','.cs':'C#',
  '.rb':'Ruby','.rs':'Rust','.php':'PHP','.sh':'Shell','.dart':'Dart',
  '.swift':'Swift','.cpp':'C++','.c':'C',
};

function detectLanguage(dir) {
  try {
    const counts = {};
    for (const f of fs.readdirSync(dir)) {
      const ext = path.extname(f).toLowerCase();
      if (EXT_MAP[ext]) counts[ext] = (counts[ext] || 0) + 1;
    }
    const top = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
    return top ? EXT_MAP[top[0]] : '';
  } catch { return ''; }
}

function scanLocalRepos() {
  ensureDir(REPOS_DIR);
  const repos = [];
  let entries = [];
  try { entries = fs.readdirSync(REPOS_DIR, { withFileTypes: true }); } catch { return repos; }
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    const rpath = path.join(REPOS_DIR, e.name);
    if (!fs.existsSync(path.join(rpath, '.git'))) continue;
    let remote = '';
    try {
      remote = execSync(`git -C "${rpath}" remote get-url origin`, {
        encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 3000,
      }).trim();
    } catch {}
    let homepage = '';
    try {
      const pkg = JSON.parse(fs.readFileSync(path.join(rpath, 'package.json'), 'utf8'));
      homepage = pkg.homepage || '';
    } catch {}
    repos.push({ name: e.name, path: rpath, remote, language: detectLanguage(rpath), cloned: true, homepage });
  }
  return repos;
}

// ── GitHub API ─────────────────────────────────────────────────────────────────
function httpsGet(url, token, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request({
      hostname: u.hostname, path: u.pathname + u.search,
      headers: { Authorization: 'token ' + token, 'User-Agent': 'TerminalMobile/1.0', Accept: 'application/vnd.github.v3+json' },
    }, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(body) }); }
        catch  { resolve({ status: res.statusCode, data: body }); }
      });
    });
    const timer = setTimeout(() => { req.destroy(); reject(new Error('GitHub API timeout')); }, timeoutMs);
    req.on('error', (e) => { clearTimeout(timer); reject(e); });
    req.on('close', () => clearTimeout(timer));
    req.end();
  });
}

async function fetchGithubRepos(token) {
  const localNames = new Set(scanLocalRepos().map(r => r.name));
  const all = [];
  let page = 1;
  while (true) {
    const { status, data } = await httpsGet(
      `https://api.github.com/user/repos?per_page=100&sort=updated&page=${page}`, token
    );
    if (status === 401) throw new Error('Token inválido ou expirado');
    if (status !== 200 || !Array.isArray(data)) break;
    if (!data.length) break;
    for (const r of data) {
      all.push({
        name: r.name, fullName: r.full_name, desc: r.description || '',
        url: r.clone_url, private: r.private, language: r.language || '',
        updatedAt: r.updated_at, stars: r.stargazers_count, fork: r.fork,
        cloned: localNames.has(r.name),
        path: path.join(REPOS_DIR, r.name),
        homepage: r.homepage || '',
        hasPages: !!r.has_pages,
      });
    }
    if (data.length < 100) break;
    page++;
  }
  return all;
}

// ── Create GitHub repo ─────────────────────────────────────────────────────────
function httpsPost(url, token, body, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const u    = new URL(url);
    const data = JSON.stringify(body);
    const req  = https.request({
      method: 'POST',
      hostname: u.hostname, path: u.pathname + u.search,
      headers: {
        Authorization: 'token ' + token,
        'User-Agent': 'TerminalMobile/1.0',
        Accept: 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
      },
    }, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(buf) }); }
        catch { resolve({ status: res.statusCode, data: buf }); }
      });
    });
    const timer = setTimeout(() => { req.destroy(); reject(new Error('GitHub API timeout')); }, timeoutMs);
    req.on('error', e => { clearTimeout(timer); reject(e); });
    req.on('close', () => clearTimeout(timer));
    req.write(data);
    req.end();
  });
}

async function createRepo(name, token, isPrivate = false) {
  const { status, data } = await httpsPost('https://api.github.com/user/repos', token, {
    name, private: isPrivate, auto_init: true,
  });
  if (status === 401) throw new Error('Token inválido ou expirado');
  if (status === 422) throw new Error('Repositório já existe ou nome inválido');
  if (status !== 201) throw new Error((data && data.message) || 'Erro ao criar repositório');
  return {
    name: data.name, fullName: data.full_name,
    url: data.clone_url, remote: data.clone_url,
    private: data.private, desc: data.description || '',
    language: data.language || '', homepage: data.homepage || '',
    cloned: false, path: path.join(REPOS_DIR, data.name),
  };
}

// ── Clone ──────────────────────────────────────────────────────────────────────
function cloneRepo(url, name) {
  return new Promise((resolve, reject) => {
    ensureDir(REPOS_DIR);
    const dest = path.join(REPOS_DIR, name);
    if (fs.existsSync(path.join(dest, '.git'))) { resolve({ path: dest }); return; }
    const proc = spawn('git', ['clone', '--depth', '1', url, dest], { stdio: 'pipe' });
    const out  = [];
    proc.stdout.on('data', d => out.push(d.toString()));
    proc.stderr.on('data', d => out.push(d.toString()));
    proc.on('close', code => {
      if (code === 0) resolve({ path: dest });
      else reject(new Error('git clone falhou:\n' + out.join('').slice(0, 300)));
    });
    proc.on('error', e => reject(new Error('git não encontrado: ' + e.message)));
  });
}

// ── Clone-all state ────────────────────────────────────────────────────────────
const cloneState = { running: false, total: 0, done: 0, current: null, errors: [] };

async function cloneAll(repos) {
  if (cloneState.running) return;
  const toClone = repos.filter(r => !r.cloned && r.url);
  if (!toClone.length) { cloneState.running = false; return; }
  cloneState.running = true;
  cloneState.total   = toClone.length;
  cloneState.done    = 0;
  cloneState.errors  = [];
  cloneState.current = null;
  for (const r of toClone) {
    cloneState.current = r.name;
    try {
      await cloneRepo(r.url, r.name);
      r.cloned = true;
      r.path   = path.join(REPOS_DIR, r.name);
    } catch (e) {
      cloneState.errors.push({ name: r.name, error: e.message });
    }
    cloneState.done++;
  }
  cloneState.running = false;
  cloneState.current = null;
}

module.exports = { scanLocalRepos, fetchGithubRepos, createRepo, cloneRepo, cloneAll, cloneState, updateEnv, REPOS_DIR };
