'use strict';
const crypto = require('crypto');

function generateToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('hex');
}

function generatePin() {
  return String(crypto.randomInt(1000, 9999));
}

function validateToken(provided, expected) {
  if (!provided || !expected) return false;
  if (provided.length !== expected.length) return false;
  try {
    return crypto.timingSafeEqual(
      Buffer.from(provided, 'utf8'),
      Buffer.from(expected, 'utf8')
    );
  } catch {
    return false;
  }
}

module.exports = { generateToken, generatePin, validateToken };
