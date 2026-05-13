'use strict';
const { EventEmitter } = require('events');
const { spawn } = require('child_process');
const os = require('os');

let nodePty = null;
try {
  nodePty = require('node-pty');
} catch {
  console.warn('[PTY] node-pty unavailable — using child_process fallback (limited)');
  console.warn('[PTY] For full terminal: npm install node-pty (needs build tools)');
}

class Session extends EventEmitter {
  constructor(id, options = {}) {
    super();
    this.id = id;
    this.cols = options.cols || 120;
    this.rows = options.rows || 40;
    this.alive = false;
    this._proc = null;
    this._usePty = false;
    this.createdAt = Date.now();
  }

  start() {
    const isWin = process.platform === 'win32';
    const shell = process.env.SHELL_PATH
      || (isWin ? 'powershell.exe' : (process.env.SHELL || '/bin/bash'));
    const initialCommand = process.env.INITIAL_COMMAND || null;

    if (nodePty) {
      this._startPty(shell, initialCommand);
    } else {
      this._startSpawn(shell, initialCommand, isWin);
    }
  }

  _startPty(shell, initialCommand) {
    try {
      this._proc = nodePty.spawn(shell, [], {
        name: 'xterm-256color',
        cols: this.cols,
        rows: this.rows,
        cwd: os.homedir(),
        env: {
          ...process.env,
          TERM: 'xterm-256color',
          COLORTERM: 'truecolor',
          LINES: String(this.rows),
          COLUMNS: String(this.cols),
        },
      });
      this._proc.onData((data) => this.emit('data', data));
      this._proc.onExit(({ exitCode, signal }) => {
        this.alive = false;
        this.emit('exit', exitCode, signal);
      });
      this.alive = true;
      this._usePty = true;
      if (initialCommand) {
        setTimeout(() => { if (this.alive) this.write(initialCommand + '\r'); }, 800);
      }
    } catch (e) {
      console.error('[PTY] node-pty spawn failed:', e.message, '— falling back');
      this._startSpawn(shell, initialCommand, process.platform === 'win32');
    }
  }

  _startSpawn(shell, initialCommand, isWin) {
    const args = isWin ? [] : ['-i'];
    this._proc = spawn(shell, args, {
      cwd: os.homedir(),
      env: { ...process.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this._proc.stdout.on('data', (d) => this.emit('data', d.toString('utf8')));
    this._proc.stderr.on('data', (d) => this.emit('data', d.toString('utf8')));
    this._proc.on('exit', (code) => { this.alive = false; this.emit('exit', code, null); });
    this._proc.on('error', (e) => this.emit('data', `\r\n[shell error: ${e.message}]\r\n`));
    this.alive = true;
    this._usePty = false;
    if (initialCommand) {
      setTimeout(() => { if (this.alive) this.write(initialCommand + '\n'); }, 400);
    }
  }

  write(data) {
    if (!this.alive || !this._proc) return false;
    try {
      if (this._usePty) {
        this._proc.write(data);
      } else {
        this._proc.stdin.write(data);
      }
      return true;
    } catch { return false; }
  }

  resize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    if (this.alive && this._usePty && this._proc) {
      try { this._proc.resize(cols, rows); } catch {}
    }
  }

  kill() {
    if (!this._proc) return;
    this.alive = false;
    try { this._proc.kill(); } catch {}
    this.emit('exit', -1, 'SIGTERM');
  }

  toInfo() {
    return { id: this.id, alive: this.alive, cols: this.cols, rows: this.rows, createdAt: this.createdAt };
  }
}

class PtyManager {
  constructor() {
    this.sessions = new Map();
    this.maxSessions = parseInt(process.env.MAX_SESSIONS || '10', 10);
  }

  create(id, options = {}) {
    for (const [sid, s] of this.sessions) {
      if (!s.alive) this.sessions.delete(sid);
    }
    const session = new Session(id, options);
    this.sessions.set(id, session);
    session.on('exit', () => setTimeout(() => this.sessions.delete(id), 10000));
    session.start();
    return session;
  }

  get(id) { return this.sessions.get(id) || null; }

  destroy(id) {
    const s = this.sessions.get(id);
    if (s) { s.kill(); this.sessions.delete(id); }
  }

  list() { return Array.from(this.sessions.values()).map((s) => s.toInfo()); }

  destroyAll() { for (const id of [...this.sessions.keys()]) this.destroy(id); }
}

module.exports = new PtyManager();
