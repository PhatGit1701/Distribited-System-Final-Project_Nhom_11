import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from '../src/api.mjs';

test('API errors retain server code, message and HTTP status', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    code: 'INVALID_CREDENTIALS', message: 'Invalid credentials', status: 401, path: '/api/node/session',
  }), { status: 401 });
  try {
    await assert.rejects(request('/session'), error => {
      assert.equal(error.code, 'INVALID_CREDENTIALS');
      assert.equal(error.message, 'Invalid credentials');
      assert.equal(error.status, 401);
      return true;
    });
  } finally { globalThis.fetch = original; }
});
