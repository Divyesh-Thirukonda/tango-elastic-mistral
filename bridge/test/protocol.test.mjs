import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFile, access } from 'node:fs/promises';
import { CodexAppServer } from '../app-server.mjs';
import { PhoneAgent, decodeScreenshot } from '../agent.mjs';
import { PHONE_POLICY } from '../policy.mjs';

function fakeCodex({ authenticated = true, runTurn } = {}) {
  const codex = new EventEmitter();
  codex.calls = []; codex.accountStatus = async () => ({ authenticated, provider: 'codex-chatgpt' });
  codex.startLogin = async mode => { codex.calls.push(['login', mode]); return { type: 'chatgptDeviceCode', userCode: 'ABCD-1234', verificationUrl: 'https://auth.openai.com/codex/device' }; };
  codex.startThread = async params => { codex.calls.push(['thread', params]); return 'thread-1'; };
  codex.runTurn = runTurn ?? (async (id, params) => { codex.calls.push(['turn', id, params]); return { text: '{"action":{"type":"tap","target":"s1:n1"}}', usage: { totalTokens: 15 } }; });
  codex.closeThread = async id => { codex.calls.push(['close', id]); };
  codex.interrupt = async id => { codex.calls.push(['interrupt', id]); };
  return codex;
}
const body = { sessionId: 'task-1', goal: 'Find the TA and save an email draft.', observation: { nodes: [{ id: 's1:n1', text: 'Search' }] }, history: [] };

test('device-code auth is native and pending login is reused without token handling', async () => {
  const codex = fakeCodex({ authenticated: false }); const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  const login = await agent.authStart();
  assert.equal(login.userCode, 'ABCD-1234'); assert.equal((await agent.authStart()).userCode, login.userCode);
  assert.deepEqual(codex.calls, [['login', 'device']]);
  codex.emit('notification', { method: 'account/login/completed', params: { success: false } });
  assert.match((await agent.status()).message, /did not complete/);
});

test('an already signed-in account does not start or mutate login', async () => {
  const codex = fakeCodex(); const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  assert.equal((await agent.authStart()).authenticated, true); assert.deepEqual(codex.calls, []);
});

test('unauthenticated inference is blocked before creating a thread or turn', async () => {
  const codex = fakeCodex({ authenticated: false }); const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  await assert.rejects(() => agent.step(body), error => error.code === 'chatgpt_sign_in_required'); assert.deepEqual(codex.calls, []);
});

test('ephemeral structured phone turns reuse the session and contain no host environment', async () => {
  const codex = fakeCodex(); const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  assert.equal((await agent.step(body)).action.target, 's1:n1'); await agent.step(body);
  const threads = codex.calls.filter(c => c[0] === 'thread'); const turns = codex.calls.filter(c => c[0] === 'turn');
  assert.equal(threads.length, 1); assert.equal(turns.length, 2);
  assert.equal(threads[0][1].baseInstructions, PHONE_POLICY); assert.equal(threads[0][1].ephemeral, true); assert.deepEqual(threads[0][1].environments, []);
  assert.equal(turns[0][2].outputSchema.properties.action.properties.target.type[0], 'string');
  assert.equal(turns[0][2].sandboxPolicy.networkAccess, false);
});

test('trusted human follow-ups preserve the current task conversation and verified facts', async () => {
  const codex = fakeCodex(); const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  await agent.step(body);
  await agent.step({ ...body, goal: body.goal + '\nFollow-up instruction from user: The club has five members.' });
  assert.equal(codex.calls.filter(c => c[0] === 'thread').length, 1);
  assert.ok(codex.calls.filter(c => c[0] === 'turn').at(-1)[2].input[0].text.includes('five members'));
});

test('screenshot is an image input in a private temp file, then removed', async () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6SqsAAAAASUVORK5CYII=', 'base64');
  let path;
  const codex = fakeCodex({ runTurn: async (id, params) => {
    path = params.input.find(input => input.type === 'localImage').path;
    assert.deepEqual(await readFile(path), png);
    assert.equal(params.input[0].text.includes(png.toString('base64')), false);
    return { text: '{"action":{"type":"home"}}' };
  } });
  await new PhoneAgent({ codex, cwd: '/tmp/work' }).step({ ...body, observation: { ...body.observation, screenshot: png.toString('base64') } });
  await assert.rejects(() => access(path));
  assert.throws(() => decodeScreenshot({ screenshot: Buffer.from('not an image').toString('base64') }), /PNG or JPEG/);
});

test('invalid model output is not executed and clears the failed conversation', async () => {
  const codex = fakeCodex({ runTurn: async () => ({ text: '{"action":{"type":"tap","target":"old:n1"}}' }) });
  const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  await assert.rejects(() => agent.step(body), /absent/); assert.equal(agent.sessions.size, 0);
  assert.ok(codex.calls.some(c => c[0] === 'close'));
});

test('simultaneous steps for the same session cannot race thread creation', async () => {
  let release; const codex = fakeCodex({ runTurn: () => new Promise(resolve => { release = () => resolve({ text: '{"action":{"type":"home"}}' }); }) });
  const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  const first = agent.step(body);
  await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(() => agent.step(body), error => error.code === 'session_busy');
  release(); await first;
  assert.equal(codex.calls.filter(c => c[0] === 'thread').length, 1);
});

test('cancel while creating a thread cannot subsequently start inference', async () => {
  let release;
  const codex = fakeCodex();
  codex.startThread = () => new Promise(resolve => { release = () => resolve('thread-1'); });
  const agent = new PhoneAgent({ codex, cwd: '/tmp/work' });
  const pending = agent.step(body);
  await new Promise(resolve => setImmediate(resolve));
  await agent.cancel(body.sessionId); release();
  await assert.rejects(() => pending, error => error.code === 'canceled');
  assert.equal(codex.calls.some(c => c[0] === 'turn'), false);
});

test('turn collector handles notifications arriving before turn/start response', async () => {
  const codex = new CodexAppServer();
  codex.request = async () => {
    codex.handleMessage({ method: 'item/completed', params: { threadId: 't', turnId: 'u', item: { id: 'a', type: 'agentMessage', text: 'commentary', phase: 'commentary' } } });
    codex.handleMessage({ method: 'thread/tokenUsage/updated', params: { threadId: 't', turnId: 'u', tokenUsage: { last: { totalTokens: 27 } } } });
    codex.handleMessage({ method: 'turn/completed', params: { threadId: 't', turn: { id: 'u', status: 'completed', items: [{ id: 'b', type: 'agentMessage', text: '{"action":{"type":"home"}}', phase: 'final_answer' }] } } });
    return { turn: { id: 'u' } };
  };
  const result = await codex.runTurn('t', { input: [] });
  assert.equal(result.text, '{"action":{"type":"home"}}'); assert.equal(result.usage.totalTokens, 27); assert.equal(codex.collectors.size, 0);
});

test('failed and interrupted model turns never return an action', async () => {
  for (const status of ['failed', 'interrupted']) {
    const codex = new CodexAppServer();
    codex.request = async method => {
      if (method === 'turn/start') codex.handleMessage({ method: 'turn/completed', params: { threadId: 't', turn: { id: 'u', status, items: [] } } });
      return { turn: { id: 'u' } };
    };
    await assert.rejects(() => codex.runTurn('t', { input: [] }), error => error.code === 'inference_failed');
  }
});

test('unexpected native host-tool items fail closed and interrupt the model', async () => {
  const codex = new CodexAppServer(); const calls = [];
  codex.request = async method => {
    calls.push(method);
    if (method === 'turn/start') {
      codex.handleMessage({ method: 'turn/started', params: { threadId: 't', turn: { id: 'u' } } });
      codex.handleMessage({ method: 'item/started', params: { threadId: 't', turnId: 'u', item: { type: 'mcpToolCall', id: 'tool-1' } } });
    }
    return { turn: { id: 'u' } };
  };
  await assert.rejects(() => codex.runTurn('t', { input: [] }), error => error.code === 'host_tool_blocked');
  assert.ok(calls.includes('turn/interrupt'));
});
