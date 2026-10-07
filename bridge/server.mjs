import http from 'node:http';
import { timingSafeEqual, randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { networkInterfaces } from 'node:os';
import { CodexAppServer } from './app-server.mjs';
import { PhoneAgent } from './agent.mjs';
import { BridgeError } from './actions.mjs';

const MAX_BODY_BYTES = 10_000_000;

export function isAuthorized(header, token) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  const actual = Buffer.from(header.slice(7));
  const expected = Buffer.from(token);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export async function loadPairingToken(stateDirectory, supplied = process.env.TANGO_PAIRING_TOKEN) {
  if (supplied) {
    if (!/^[A-Za-z0-9_-]{32,256}$/.test(supplied)) throw new Error('TANGO_PAIRING_TOKEN must have 32 to 256 letters, numbers, underscores or hyphens.');
    return supplied;
  }
  const path = join(stateDirectory, 'pairing-token');
  try {
    const token = (await readFile(path, 'utf8')).trim();
    if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error('Invalid pairing-token file. Delete that file to create a new token.');
    return token;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const token = randomBytes(32).toString('base64url');
  try { await writeFile(path, token + '\n', { mode: 0o600, flag: 'wx' }); }
  catch (error) { if (error.code === 'EEXIST') return loadPairingToken(stateDirectory); throw error; }
  return token;
}

async function readJson(request) {
  const contentType = request.headers['content-type'];
  if (contentType && !/^application\/json(?:\s*;|$)/i.test(contentType)) throw new BridgeError('Content-Type must be application/json.', 415);
  const declared = Number(request.headers['content-length'] ?? 0);
  if (declared > MAX_BODY_BYTES) throw new BridgeError('Request exceeds 10 MB.', 413, 'body_too_large');
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new BridgeError('Request exceeds 10 MB.', 413, 'body_too_large');
    chunks.push(chunk);
  }
  if (!size) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new BridgeError('Request must contain valid JSON.'); }
}

function send(response, status, data) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
  });
  response.end(JSON.stringify(data));
}

export function createBridgeServer({ token, agent }) {
  if (!token || !agent) throw new Error('The bridge requires a pairing token and an agent.');
  const server = http.createServer(async (request, response) => {
    if (!isAuthorized(request.headers.authorization, token)) {
      response.setHeader('WWW-Authenticate', 'Bearer');
      send(response, 401, { error: { code: 'unauthorized', message: 'Enter the bridge pairing token in Tango.' } });
      return;
    }
    // Native Android HTTP clients do not set Origin. Reject browser access;
    // do not expose the bridge as a website API or enable permissive CORS.
    if (request.headers.origin) { send(response, 403, { error: { code: 'browser_access_denied', message: 'Use the Tango Android client.' } }); return; }
    const abortController = new AbortController();
    const aborted = () => { if (!response.writableEnded) abortController.abort(); };
    request.once('aborted', aborted); response.once('close', aborted);
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      if (request.method === 'GET' && path === '/health') {
        const status = await agent.status();
        send(response, 200, { ok: true, authenticated: status.authenticated, provider: status.provider, model: status.model });
      } else if (request.method === 'GET' && path === '/auth/status') {
        send(response, 200, await agent.status());
      } else if (request.method === 'POST' && path === '/auth/start') {
        const body = await readJson(request);
        send(response, 200, await agent.authStart(body.mode ?? 'device'));
      } else if (request.method === 'POST' && path === '/v1/step') {
        const body = await readJson(request);
        send(response, 200, await agent.step(body, { signal: abortController.signal }));
      } else if (request.method === 'POST' && path === '/v1/cancel') {
        const body = await readJson(request);
        if (typeof body.sessionId !== 'string' || !/^[\w:.-]{1,128}$/.test(body.sessionId)) throw new BridgeError('A valid sessionId is required.');
        send(response, 200, await agent.cancel(body.sessionId));
      } else send(response, 404, { error: { code: 'not_found', message: 'Unknown bridge endpoint.' } });
    } catch (error) {
      send(response, error.status ?? 500, { error: { code: error.code ?? 'bridge_error', message: error instanceof BridgeError ? error.message : 'The bridge could not complete this request. Retry or restart it.' } });
    } finally {
      request.removeListener('aborted', aborted); response.removeListener('close', aborted);
    }
  });
  server.headersTimeout = 15_000;
  server.requestTimeout = 120_000;
  server.keepAliveTimeout = 5_000;
  return server;
}

export async function main() {
  const directory = dirname(fileURLToPath(import.meta.url));
  const stateDirectory = resolve(process.env.TANGO_STATE_DIR || join(directory, '.state'));
  const codexHome = resolve(process.env.TANGO_CODEX_HOME || join(stateDirectory, 'codex'));
  const cwd = join(stateDirectory, 'work');
  await Promise.all([mkdir(stateDirectory, { recursive: true, mode: 0o700 }), mkdir(codexHome, { recursive: true, mode: 0o700 }), mkdir(cwd, { recursive: true, mode: 0o700 })]);
  const token = await loadPairingToken(stateDirectory);
  const codex = new CodexAppServer({ executable: process.env.TANGO_CODEX_BIN || 'codex', codexHome, cwd });
  const agent = new PhoneAgent({ codex, cwd });
  const server = createBridgeServer({ token, agent });
  const host = process.env.TANGO_HOST || (process.argv.includes('--lan') ? '0.0.0.0' : '127.0.0.1');
  const port = Number(process.env.TANGO_PORT || 8787);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('TANGO_PORT must be a TCP port.');
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  console.log(`Tango bridge listening on ${host}:${port}`);
  if (host === '0.0.0.0') {
    for (const interfaces of Object.values(networkInterfaces())) {
      for (const info of interfaces ?? []) if (info.family === 'IPv4' && !info.internal) console.log(`Phone bridge URL: http://${info.address}:${port}`);
    }
  } else console.log(`Bridge URL: http://${host}:${port} (use --lan for a phone on trusted Wi-Fi)`);
  console.log(`Pairing token: ${token}`);
  console.log('Paste that URL and token into Tango, then tap Sign in with ChatGPT.');
  console.log('Keep this terminal open. No inference runs until you start a phone task.');
  const shutdown = () => { codex.close(); server.close(); server.closeAllConnections(); };
  process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
  return { server, agent, codex };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
