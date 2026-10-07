import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createBridgeServer, isAuthorized, loadPairingToken } from '../server.mjs';
import { BridgeError } from '../actions.mjs';

const token = 'a'.repeat(43);
async function withServer(fn) {
  const calls = [];
  const agent = {
    status: async () => { calls.push('status'); return { authenticated: true, provider: 'codex-chatgpt', model: 'test-model' }; },
    authStart: async mode => { calls.push('authStart'); return { verificationUrl: 'https://auth.openai.com/codex/device', userCode: 'ABCD-1234', message: mode }; },
    step: async body => { calls.push('step'); if (!body.goal) throw new BridgeError('goal required'); return { action: { type: 'home' } }; },
    cancel: async () => ({ ok: true }),
  };
  const server = createBridgeServer({ token, agent });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const url = `http://127.0.0.1:${server.address().port}`;
  try { await fn(url, calls); }
  finally { await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }); }
}

test('pairing authentication uses complete bearer token', () => {
  assert.ok(isAuthorized(`Bearer ${token}`, token));
  for (const input of [undefined, `Basic ${token}`, `Bearer ${token}x`, `Bearer ${'b'.repeat(43)}`, 'Bearer ']) assert.equal(isAuthorized(input, token), false);
});

test('pairing token is random, persisted, and owner-readable only', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tango-token-'));
  try {
    const first = await loadPairingToken(dir, undefined);
    assert.match(first, /^[\w-]{43}$/);
    assert.equal(await loadPairingToken(dir, undefined), first);
    if (process.platform !== 'win32') assert.equal((await stat(join(dir, 'pairing-token'))).mode & 0o777, 0o600);
    await assert.rejects(() => loadPairingToken(dir, 'weak'), /32/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('every endpoint denies unauthenticated calls before touching Codex', async () => withServer(async (url, calls) => {
  for (const [method, path] of [['GET', '/health'], ['GET', '/auth/status'], ['POST', '/auth/start'], ['POST', '/v1/step'], ['POST', '/v1/cancel']]) {
    const response = await fetch(url + path, { method });
    assert.equal(response.status, 401); assert.equal((await response.json()).error.code, 'unauthorized');
  }
  assert.deepEqual(calls, []);
}));

test('authenticated health, device login, status and action response follow Android contract', async () => withServer(async url => {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const health = await fetch(url + '/health', { headers });
  assert.deepEqual(await health.json(), { ok: true, authenticated: true, provider: 'codex-chatgpt', model: 'test-model' });
  assert.equal(health.headers.get('cache-control'), 'no-store');
  const login = await fetch(url + '/auth/start', { method: 'POST', headers, body: '{}' });
  assert.equal((await login.json()).userCode, 'ABCD-1234');
  const action = await fetch(url + '/v1/step', { method: 'POST', headers, body: JSON.stringify({ goal: 'go home' }) });
  assert.deepEqual(await action.json(), { action: { type: 'home' } });
  const cancel = await fetch(url + '/v1/cancel', { method: 'POST', headers, body: '{"sessionId":"task-1"}' });
  assert.deepEqual(await cancel.json(), { ok: true });
}));

test('browser origins, malformed JSON, incompatible content types and unknown routes fail closed', async () => withServer(async (url, calls) => {
  const auth = { Authorization: `Bearer ${token}` };
  const crossOrigin = await fetch(url + '/health', { headers: { ...auth, Origin: 'https://malicious.example' } });
  assert.equal(crossOrigin.status, 403); assert.equal(crossOrigin.headers.get('access-control-allow-origin'), null); assert.deepEqual(calls, []);
  const badJson = await fetch(url + '/v1/step', { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: '{' });
  assert.equal(badJson.status, 400);
  const wrongType = await fetch(url + '/v1/step', { method: 'POST', headers: { ...auth, 'Content-Type': 'text/plain' }, body: '{}' });
  assert.equal(wrongType.status, 415);
  assert.equal((await fetch(url + '/elsewhere', { headers: auth })).status, 404);
}));
