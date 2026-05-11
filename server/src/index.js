'use strict';
require('dotenv').config();
const { createServer } = require('./wsServer');
const { generatePin } = require('./auth');
const os = require('os');

const PORT = parseInt(process.env.PORT ?? '8765', 10);
let TOKEN = process.env.AUTH_TOKEN?.trim();

if (!TOKEN) {
  TOKEN = generatePin();
  console.log('\n' + '═'.repeat(40));
  console.log('  TerminalMobile Server');
  console.log('═'.repeat(40));
  console.log('  PIN gerado automaticamente:\n');
  console.log('       ' + TOKEN + '\n');
  console.log('  Para fixar, adicione ao .env:');
  console.log('  AUTH_TOKEN=' + TOKEN);
  console.log('═'.repeat(40) + '\n');
} else {
  console.log('\n' + '═'.repeat(40));
  console.log('  TerminalMobile Server');
  console.log('═'.repeat(40));
  console.log('  PIN: ' + TOKEN);
  console.log('═'.repeat(40) + '\n');
}

const server = createServer(PORT, TOKEN);

const ifaces = os.networkInterfaces();
console.log('[Server] Ready on:');
for (const list of Object.values(ifaces ?? {})) {
  for (const iface of list ?? []) {
    if (iface.family === 'IPv4' && !iface.internal) {
      console.log(`  ws://${iface.address}:${PORT}   ← use this in Android app`);
    }
  }
}
console.log(`  ws://localhost:${PORT}`);
console.log('[Server] Press Ctrl+C to stop\n');

function shutdown() {
  console.log('\n[Server] Shutting down...');
  require('./ptyManager').destroyAll();
  server.close(() => { console.log('[Server] Done.'); process.exit(0); });
  setTimeout(() => process.exit(1), 5000);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('uncaughtException', (e) => console.error('[!] Uncaught:', e.message));
process.on('unhandledRejection', (e) => console.error('[!] Unhandled rejection:', e));
