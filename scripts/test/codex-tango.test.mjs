import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { PassThrough } from 'node:stream';
import { connectionConfig, requestPhone, TOOLS, callTool, handleRpc, runMcp } from '../codex-tango.mjs';

const TOKEN = 'a'.repeat(43);
const config = connectionConfig({ TANGO_CONNECTION_TOKEN: TOKEN });

test('connection accepts only loopback and a current memory token', () => {
  assert.equal(config.endpoint.href, 'http://127.0.0.1:8766/');
  for (const url of ['http://192.168.1.5:8766', 'https://127.0.0.1:8766', 'http://localhost:8766', 'http://user:pass@127.0.0.1:8766', 'http://127.0.0.1:8766/path']) {
    assert.throws(() => connectionConfig({ TANGO_CONNECTION_TOKEN: TOKEN, TANGO_CONNECTION_URL: url }), /127.0.0.1/);
  }
  assert.throws(() => connectionConfig({}), /current token/);
  assert.throws(() => connectionConfig({ TANGO_CONNECTION_TOKEN: 'old-short-token' }), /current token/);
});

test('real transport sends token, content length and JSON, and exposes phone errors', async t => {
  const seen = [];
  const server = http.createServer(async (req, res) => {
    let body = ''; for await (const chunk of req) body += chunk;
    seen.push({ path: req.url, headers: req.headers, body });
    if (req.url === '/v1/stop') { res.writeHead(409); res.end(JSON.stringify({ error: { message: 'Thread changed.' } })); }
    else { res.writeHead(200); res.end(JSON.stringify({ accepted: true, threadId: 'one' })); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const local = { endpoint: new URL(`http://127.0.0.1:${server.address().port}`), token: TOKEN };
  assert.deepEqual(await requestPhone(local, 'POST', '/v1/prompts', { prompt: 'Check Settings' }), { accepted: true, threadId: 'one' });
  assert.equal(seen[0].headers.authorization, `Bearer ${TOKEN}`);
  assert.equal(seen[0].headers['content-type'], 'application/json');
  assert.equal(Number(seen[0].headers['content-length']), Buffer.byteLength(seen[0].body));
  assert.equal(seen[0].headers['transfer-encoding'], undefined);
  await assert.rejects(requestPhone(local, 'POST', '/v1/stop', { threadId: 'one' }), error => error.status === 409 && error.message === 'Thread changed.');
});

test('every tool routes to an authenticated phone endpoint with correct thread and mode', async () => {
  const calls = [];
  const transport = async (_, method, path, body) => { calls.push({ method, path, body }); return { accepted: true }; };
  for (const name of ['tango_status', 'tango_threads']) await callTool(config, name, {}, transport);
  await callTool(config, 'tango_thread', { threadId: 'fixture-thread' }, transport);
  await callTool(config, 'tango_prompt', { prompt: 'Test my app', mode: 'high' }, transport);
  for (const name of ['tango_steer', 'tango_respond']) await callTool(config, name, { threadId: 'fixture-thread', reply: 'Continue' }, transport);
  for (const name of ['tango_pause', 'tango_stop']) await callTool(config, name, { threadId: 'fixture-thread' }, transport);
  assert.deepEqual(calls.map(call => [call.method, call.path]), [
    ['GET', '/v1/status'], ['GET', '/v1/threads'], ['GET', '/v1/threads/fixture-thread'], ['POST', '/v1/prompts'],
    ['POST', '/v1/steer'], ['POST', '/v1/respond'], ['POST', '/v1/pause'], ['POST', '/v1/stop'],
  ]);
  assert.deepEqual(calls[3].body, { prompt: 'Test my app', mode: 'high' });
  for (const call of calls.filter(call => call.method === 'GET')) assert.equal(call.body, undefined);
});

test('tools reject missing or malformed control arguments before making requests', async () => {
  let sent = 0;
  const transport = async () => { sent++; return {}; };
  await assert.rejects(callTool(config, 'tango_stop', {}, transport), /threadId/);
  await assert.rejects(callTool(config, 'tango_thread', { threadId: '../other-account' }, transport), /Invalid threadId/);
  await assert.rejects(callTool(config, 'tango_prompt', { prompt: 'Test', mode: 'work' }, transport), /Mode/);
  await assert.rejects(callTool(config, 'tango_prompt', { prompt: ' ' }, transport), /nonempty/);
  await assert.rejects(callTool(config, 'tango_prompt', { prompt: 'x'.repeat(16001) }, transport), /nonempty/);
  await assert.rejects(callTool(config, 'tango_pause', { threadId: 'test', token: 'injected' }, transport), /Unsupported/);
  assert.equal(sent, 0);
});

test('observe returns a native MCP image without duplicating screenshot base64 in text', async () => {
  const result = await callTool(config, 'tango_observe', {}, async () => ({ observation: { packageName: 'com.example.app', screenshot: 'pixelbytes', screenshotMimeType: 'image/jpeg', nodes: [] } }));
  assert.equal(result.content.length, 2);
  assert.deepEqual(result.content[1], { type: 'image', mimeType: 'image/jpeg', data: 'pixelbytes' });
  assert.equal(result.content[0].text.includes('pixelbytes'), false);
  const protectedScreen = await callTool(config, 'tango_observe', {}, async () => ({ observation: { password: true, screenshotError: 'Withheld' } }));
  assert.equal(protectedScreen.content.length, 1);
  assert.equal(JSON.parse(protectedScreen.content[0].text).observation.password, true);
});

test('MCP initialization, tool discovery, notification handling and errors follow JSON-RPC', async () => {
  const initialize = await handleRpc(config, { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } });
  assert.equal(initialize.result.protocolVersion, '2025-06-18');
  const tools = await handleRpc(config, { jsonrpc: '2.0', id: 2, method: 'tools/list' });
  assert.equal(tools.result.tools.length, 9);
  assert.deepEqual(tools.result.tools.map(tool => tool.name), TOOLS.map(tool => tool.name));
  assert.equal(await handleRpc(config, { jsonrpc: '2.0', method: 'notifications/initialized' }), null);
  const failure = await handleRpc(config, { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'tango_status' } }, async () => { throw new Error('Connection revoked.'); });
  assert.equal(failure.result.isError, true);
  assert.equal(failure.result.content[0].text, 'Connection revoked.');
  assert.equal((await handleRpc(config, { jsonrpc: '2.0', id: 4, method: 'unknown' })).error.code, -32601);
});

test('stdio server writes only valid JSON-RPC messages and recovers after invalid JSON', async () => {
  const input = new PassThrough(), output = new PassThrough(); let emitted = '';
  output.on('data', chunk => emitted += chunk);
  const running = runMcp(config, input, output);
  input.end('garbage\n{"jsonrpc":"2.0","method":"notifications/initialized"}\n{"jsonrpc":"2.0","id":7,"method":"ping"}\n');
  await running;
  const lines = emitted.trim().split('\n').map(line => JSON.parse(line));
  assert.equal(lines.length, 2);
  assert.equal(lines[0].error.code, -32700);
  assert.deepEqual(lines[1], { jsonrpc: '2.0', id: 7, result: {} });
  assert.equal(emitted.includes(TOKEN), false);
});
