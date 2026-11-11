import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createServer } from 'vite';
import config from '../vite.config.js';

test('dev proxy preserves same-origin login and rejects foreign origins', async () => {
  // Exercise the real Vite proxy against the same Origin/Host rule as NodeSessionFilter.
  const backend = http.createServer((req, res) => {
    res.statusCode = req.headers.origin === `http://${req.headers.host}` ? 200 : 403;
    res.end();
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  let vite;
  try {
    const target = `http://127.0.0.1:${backend.address().port}`;
    const api = config.server.proxy['/api'];
    vite = await createServer({
      configFile: false,
      server: { host: '127.0.0.1', port: 0, proxy: {
        '/api': typeof api === 'string' ? target : { ...api, target },
      } },
    });
    await vite.listen();
    const origin = `http://127.0.0.1:${vite.httpServer.address().port}`;
    const login = await fetch(`${origin}/api/node/session`, { method: 'POST', headers: { Origin: origin } });
    assert.equal(login.status, 200);
    const foreign = await fetch(`${origin}/api/node/session`, { method: 'POST', headers: { Origin: 'http://foreign.example' } });
    assert.equal(foreign.status, 403);
  } finally {
    await vite?.close();
    backend.closeAllConnections();
    await new Promise(resolve => backend.close(resolve));
  }
});
