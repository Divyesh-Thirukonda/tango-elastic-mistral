#!/usr/bin/env node
import http from 'node:http';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

export function connectionConfig(env = process.env) {
  const endpoint = new URL(env.TANGO_CONNECTION_URL || 'http://127.0.0.1:8766');
  if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1' || endpoint.username || endpoint.password
      || endpoint.pathname !== '/' || endpoint.search || endpoint.hash) {
    throw new Error('TANGO_CONNECTION_URL must be an HTTP URL on 127.0.0.1 with no credentials or path.');
  }
  const token = env.TANGO_CONNECTION_TOKEN;
  if (!/^[A-Za-z0-9_-]{43}$/.test(token || '')) {
    throw new Error('Set TANGO_CONNECTION_TOKEN to the current token shown under Connect to Codex in Tango.');
  }
  return { endpoint, token };
}

/** Built-in HTTP transport always sends Content-Length, as the phone intentionally rejects chunked input. */
export function requestPhone(config, method, path, body) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const request = http.request(new URL(path, config.endpoint), {
      method,
      headers: { Authorization: `Bearer ${config.token}`,
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {}) },
      timeout: 15000,
    }, response => {
      const chunks = []; let size = 0;
      response.on('data', chunk => {
        size += chunk.length;
        if (size > 5_000_000) { request.destroy(new Error('Tango response exceeds 5 MB.')); return; }
        chunks.push(chunk);
      });
      response.on('error', reject);
      response.on('end', () => {
        try {
          const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (response.statusCode < 200 || response.statusCode >= 300) {
            const error = new Error(result.error?.message || `Tango returned HTTP ${response.statusCode}.`);
            error.status = response.statusCode; reject(error);
          } else resolve(result);
        } catch (error) { reject(error); }
      });
    });
    request.on('timeout', () => request.destroy(new Error('Tango timed out. Check USB forwarding and the connection on the phone.')));
    request.on('error', error => reject(error.code === 'ECONNREFUSED'
      ? new Error('Tango is unavailable. Enable Connect to Codex on the phone and run adb forward tcp:8766 tcp:8766.') : error));
    if (payload) request.write(payload);
    request.end();
  });
}

const objectSchema = properties => ({ type: 'object', properties, additionalProperties: false });
const text = description => ({ type: 'string', description, minLength: 1, maxLength: 16000 });
const threadId = text('Thread ID returned by tango_prompt or tango_threads.');
const control = (name, description, withReply) => ({
  name, description,
  inputSchema: { ...objectSchema({ threadId, ...(withReply ? { reply: text('Follow-up instruction or answer to Tango.') } : {}) }),
    required: withReply ? ['threadId', 'reply'] : ['threadId'] },
  annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: !withReply, openWorldHint: true },
});
export const TOOLS = [
  { name: 'tango_status', description: 'Read the active Tango thread, progress, blocker and model mode. Does not use inference.', inputSchema: objectSchema({}), annotations: { readOnlyHint: true } },
  { name: 'tango_threads', description: 'List the signed-in account’s Tango threads.', inputSchema: objectSchema({}), annotations: { readOnlyHint: true } },
  { name: 'tango_thread', description: 'Read a Tango thread including its journal and result.', inputSchema: { ...objectSchema({ threadId }), required: ['threadId'] }, annotations: { readOnlyHint: true } },
  { name: 'tango_observe', description: 'Read the current phone accessibility tree and screenshot. Uses no model inference. Locked, password and secure screens remain protected.', inputSchema: objectSchema({}), annotations: { readOnlyHint: true } },
  { name: 'tango_prompt', description: 'Ask Tango to operate or test the Android phone. Uses Tango’s signed-in ChatGPT account and phone safeguards. Omitting threadId starts a new thread. Do not supply secrets or invent authorization. If another thread is running, steer or pause it first.',
    inputSchema: { ...objectSchema({ prompt: text('Task instructions and scope for Tango.'), threadId,
      mode: { type: 'string', enum: ['instant', 'medium', 'high'], description: 'Optional model mode; also updates the phone’s slider selection.' } }), required: ['prompt'] },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true } },
  control('tango_steer', 'Send another instruction to the active Tango thread, preserving its context.', true),
  control('tango_respond', 'Answer the active Tango thread’s question or resume a paused thread with instructions. Human-only security checks stay on the phone.', true),
  control('tango_pause', 'Pause the active Tango thread. The same thread can be resumed later.', false),
  control('tango_stop', 'Stop the active Tango thread and cancel pending phone actions.', false),
];

function validateArgs(name, args) {
  const tool = TOOLS.find(candidate => candidate.name === name);
  if (!tool) throw new Error(`Unknown Tango tool: ${name}`);
  if (args === null || Array.isArray(args) || typeof args !== 'object') throw new Error('Tool arguments must be an object.');
  const allowed = tool.inputSchema.properties;
  if (Object.keys(args).some(key => !Object.hasOwn(allowed, key))) throw new Error('Unsupported tool argument.');
  for (const required of tool.inputSchema.required || []) if (!Object.hasOwn(args, required)) throw new Error(`Specify ${required}.`);
  for (const [key, value] of Object.entries(args)) {
    if (typeof value !== 'string' || !value.trim() || value.length > 16000) throw new Error(`Specify ${key} as nonempty text.`);
    if (key === 'threadId' && !/^[A-Za-z0-9_-]{1,80}$/.test(value)) throw new Error('Invalid threadId.');
    if (key === 'mode' && !['instant', 'medium', 'high'].includes(value)) throw new Error('Mode must be instant, medium or high.');
  }
}
export async function callTool(config, name, args = {}, transport = requestPhone) {
  validateArgs(name, args);
  const paths = { tango_status: '/v1/status', tango_threads: '/v1/threads', tango_observe: '/v1/observe',
    tango_prompt: '/v1/prompts', tango_steer: '/v1/steer', tango_respond: '/v1/respond', tango_pause: '/v1/pause', tango_stop: '/v1/stop' };
  const path = name === 'tango_thread' ? `/v1/threads/${args.threadId}` : paths[name];
  const method = ['tango_status', 'tango_threads', 'tango_thread', 'tango_observe'].includes(name) ? 'GET' : 'POST';
  const result = await transport(config, method, path, method === 'POST' ? args : undefined);
  if (name === 'tango_observe') {
    const { screenshot, screenshotMimeType, ...observation } = result.observation || {};
    const content = [{ type: 'text', text: JSON.stringify({ observation }) }];
    if (typeof screenshot === 'string' && screenshot && ['image/jpeg', 'image/png'].includes(screenshotMimeType)) {
      content.push({ type: 'image', mimeType: screenshotMimeType, data: screenshot });
    }
    return { content };
  }
  return { content: [{ type: 'text', text: JSON.stringify(result) }] };
}

export async function handleRpc(config, message, transport = requestPhone) {
  const id = message.id;
  if (message.jsonrpc !== '2.0' || typeof message.method !== 'string') return { jsonrpc: '2.0', id: id ?? null, error: { code: -32600, message: 'Invalid JSON-RPC request.' } };
  if (id === undefined) return null;
  let result;
  if (message.method === 'initialize') {
    const requested = message.params?.protocolVersion;
    result = { protocolVersion: ['2024-11-05', '2025-03-26', '2025-06-18'].includes(requested) ? requested : '2025-06-18',
      capabilities: { tools: {} }, serverInfo: { name: 'tango-phone', version: '1.0.0' },
      instructions: 'Tango controls the connected Android phone within its signed-in account and safeguards. Check status and observe, send a scoped task, then read its journal and steer or answer as needed. Passwords, MFA and other human-only security checks must be handled on the phone.' };
  } else if (message.method === 'ping') result = {};
  else if (message.method === 'tools/list') result = { tools: TOOLS };
  else if (message.method === 'tools/call') {
    try { result = await callTool(config, message.params?.name, message.params?.arguments || {}, transport); }
    catch (error) { result = { isError: true, content: [{ type: 'text', text: error.message }] }; }
  } else return { jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found.' } };
  return { jsonrpc: '2.0', id, result };
}

export async function runMcp(config, input = process.stdin, output = process.stdout) {
  const lines = createInterface({ input, crlfDelay: Infinity });
  let pending = Promise.resolve();
  // Preserve request ordering for phone mutations; notifications generate no stdout output.
  for await (const line of lines) {
    if (!line.trim()) continue;
    pending = pending.then(async () => {
      let response;
      try {
        if (line.length > 70000) throw new Error('MCP message is too large.');
        response = await handleRpc(config, JSON.parse(line));
      } catch { response = { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON message.' } }; }
      if (response) output.write(`${JSON.stringify(response)}\n`);
    });
  }
  await pending;
}

export async function main(args = process.argv.slice(2)) {
  if (args[0] === '--help' || !args.length) {
    process.stdout.write('Tango USB client\nSet TANGO_CONNECTION_TOKEN to the token shown on the phone.\nCommands: mcp | status | threads | thread ID | observe | prompt TEXT [THREAD_ID] [MODE] | steer ID TEXT | respond ID TEXT | pause ID | stop ID\n');
    return;
  }
  const config = connectionConfig();
  if (args[0] === 'mcp') return runMcp(config);
  const command = args[0], name = `tango_${command}`;
  let input = {};
  if (command === 'prompt') input = { prompt: args[1], ...(args[2] ? { threadId: args[2] } : {}), ...(args[3] ? { mode: args[3] } : {}) };
  else if (['steer', 'respond'].includes(command)) input = { threadId: args[1], reply: args[2] };
  else if (['thread', 'pause', 'stop'].includes(command)) input = { threadId: args[1] };
  // CLI preserves raw screenshot metadata/base64 for callers that want machine-readable observation data.
  validateArgs(name, input);
  const cliResult = command === 'observe' ? await requestPhone(config, 'GET', '/v1/observe') : await callTool(config, name, input);
  process.stdout.write(`${JSON.stringify(command === 'observe' ? cliResult : JSON.parse(cliResult.content[0].text), null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
