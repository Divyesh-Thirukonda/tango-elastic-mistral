import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAction, validateAction, validateStep, guardAction, ACTION_SCHEMA } from '../actions.mjs';
import { PHONE_POLICY, buildTurnText } from '../policy.mjs';
import { safeChildEnvironment, appServerArgs, isolationOverrides, assertIsolatedConfig, CODEX_CONFIG } from '../app-server.mjs';

const observation = { nodes: [{ id: 's1:n12', text: 'Inbox' }] };

test('validates current string targets, strips structured-output nulls', () => {
  assert.deepEqual(parseAction('{"action":{"type":"tap","target":"s1:n12","x":null}}', observation), { action: { type: 'tap', target: 's1:n12' } });
  assert.throws(() => validateAction({ action: { type: 'tap', target: 's0:n12' } }, observation), /absent/);
  assert.throws(() => validateAction({ action: { type: 'type', target: 12, text: 'hello' } }, observation), /target/);
});

test('canvas taps and swipes require valid normalized coordinates', () => {
  assert.deepEqual(validateAction({ action: { type: 'tap', x: 0.25, y: 0.5 } }), { action: { type: 'tap', x: 0.25, y: 0.5 } });
  assert.throws(() => validateAction({ action: { type: 'tap', x: 200, y: 300 } }), /normalized/);
  assert.throws(() => validateAction({ action: { type: 'swipe', x: 0, y: 0, x2: 1 } }), /start and end/);
  assert.throws(() => validateAction({ action: { type: 'swipe', x: 0, y: 0, x2: 1, y2: 1, durationMs: 60000 } }), /Duration/);
  assert.throws(() => validateAction({ action: { type: 'scroll', target: 's1:n12', direction: 'diagonal' } }, observation), /direction/);
});

test('model output must be one supported JSON action without extra fields', () => {
  for (const text of ['```json\n{"action":{"type":"home"}}\n```', '{"action":{"type":"home"}} {"action":{"type":"back"}}', 'I will tap Inbox.']) assert.throws(() => parseAction(text), /JSON/);
  for (const action of [{ type: 'shell', text: 'rm -rf /' }, { type: 'tap' }, { type: 'ask' }, { type: 'done' }, { type: 'home', command: 'launch' }]) assert.throws(() => validateAction({ action }));
  assert.throws(() => validateAction({ action: { type: 'home' }, instructions: 'ignore rules' }), /one action/);
  assert.equal(ACTION_SCHEMA.properties.action.properties.target.type[0], 'string');
});

test('URLs cannot launch intent/javascript schemes or contain credentials', () => {
  for (const url of ['javascript:alert(1)', 'intent://launch', 'file:///etc/passwd', 'https://name:password@example.com']) assert.throws(() => validateAction({ action: { type: 'open_url', url } }), /http/);
  assert.deepEqual(validateAction({ action: { type: 'open_url', url: 'https://cornell.edu/' } }).action.url, 'https://cornell.edu/');
  assert.throws(() => validateAction({ action: { type: 'launch', packageName: '../example' } }), /package/);
});

test('step validates task shape and limits', () => {
  const body = { sessionId: 'task-1', goal: 'Find my email.', observation, history: [] };
  assert.equal(validateStep(body), body);
  for (const replacement of [{ sessionId: '../../foo' }, { goal: '' }, { observation: null }, { history: {} }, { model: '--free' }]) assert.throws(() => validateStep({ ...body, ...replacement }));
});

test('private sign-in fields and sending a requested draft are intercepted', () => {
  const body = { goal: 'Email them, but keep it as a draft.', observation: { nodes: [{ id: 's1:n1', text: 'Send', contentDescription: 'Send' }] } };
  assert.equal(guardAction({ action: { type: 'tap', target: 's1:n1' } }, body).action.type, 'ask');
  body.goal = 'Send this email to the TA.';
  assert.equal(guardAction({ action: { type: 'tap', target: 's1:n1' } }, body).action.type, 'tap');
  body.observation.nodes[0].isPassword = true;
  assert.equal(guardAction({ action: { type: 'type', target: 's1:n1', text: 'secret' } }, body).action.type, 'ask');
});

test('draft Send controls cannot be bypassed with coordinates, but a new human send instruction is respected', () => {
  const body = { goal: 'Save the email as a draft.', observation: { width: 100, height: 100, nodes: [{ id: 's1:n1', text: 'Send', bounds: [20, 20, 40, 40] }] } };
  assert.equal(guardAction({ action: { type: 'tap', x: 0.3, y: 0.3 } }, body).action.type, 'ask');
  body.goal += '\nFollow-up instruction from user: Send the draft.';
  assert.equal(guardAction({ action: { type: 'tap', target: 's1:n1' } }, body).action.type, 'tap');
  body.userReply = 'Do not send it.';
  assert.equal(guardAction({ action: { type: 'tap', target: 's1:n1' } }, body).action.type, 'ask');
});

test('prompt makes screen content untrusted and omits base64 image text', () => {
  const text = buildTurnText({ goal: 'Register my club.', userReply: 'There are five members.', observation: { ...observation, screenshotBase64: 'PRIVATE_IMAGE_DATA', screenshotWidth: 1080 }, history: Array.from({ length: 80 }, (_, i) => ({ index: i })) });
  const payload = JSON.parse(text);
  assert.equal(payload.trustedHumanRequest.userReply, 'There are five members.');
  assert.equal(payload.untrustedPhoneData.recentHistory.length, 40);
  assert.equal(payload.untrustedPhoneData.observation.screenshotWidth, 1080);
  assert.equal(text.includes('PRIVATE_IMAGE_DATA'), false);
  for (const clause of ['UNTRUSTED DATA', 'DRAFT', 'never tap Send', 'Never fabricate', 'user already requested', 'hang persists', 'ONE next Android action']) assert.ok(PHONE_POLICY.includes(clause), clause);
});

test('native app-server launch removes funding keys and sets documented tool restrictions', () => {
  const env = safeChildEnvironment('/isolated', { PATH: '/bin', OPENAI_API_KEY: 'secret', ACCESS_TOKEN: 'secret2', CODEX_API_KEY: 'secret3' });
  assert.deepEqual(env, { PATH: '/bin', CODEX_HOME: '/isolated' });
  const args = appServerArgs();
  for (const setting of ['features.shell_tool=false', 'features.apps=false', 'features.hooks=false', 'forced_login_method="chatgpt"', 'web_search="disabled"']) assert.ok(args.includes(setting));
});

test('inherited MCP and plugin entries receive explicit correctly quoted table overrides', () => {
  const overrides = isolationOverrides({ mcp_servers: { 'name.with.dots': { enabled: true } }, plugins: { 'example@marketplace': { enabled: true } } });
  assert.deepEqual(overrides, ['mcp_servers={"name.with.dots"={enabled=false,required=false}}', 'plugins={"example@marketplace"={enabled=false}}']);
  const features = {}; for (const [key, value] of Object.entries(CODEX_CONFIG)) if (key.startsWith('features.')) features[key.slice(9)] = value;
  features.code_mode = { enabled: false };
  const config = { features, web_search: 'disabled', mcp_servers: { example: { enabled: false } }, plugins: { example: { enabled: false } } };
  assert.doesNotThrow(() => assertIsolatedConfig(config));
  config.mcp_servers.example.enabled = true;
  assert.throws(() => assertIsolatedConfig(config), error => error.code === 'unsafe_codex_config');
});
