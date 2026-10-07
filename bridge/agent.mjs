import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeError, ACTION_SCHEMA, parseAction, validateStep, guardAction } from './actions.mjs';
import { PHONE_POLICY, buildTurnText } from './policy.mjs';

export function decodeScreenshot(observation) {
  let encoded = observation.screenshotBase64 ?? observation.screenshot;
  if (!encoded) return null;
  if (typeof encoded === 'object') encoded = encoded.base64;
  if (typeof encoded !== 'string') throw new BridgeError('Screenshot must be a base64 PNG or JPEG.');
  encoded = encoded.replace(/^data:image\/(png|jpeg|jpg);base64,/, '');
  if (!/^[A-Za-z0-9+/\r\n]*={0,2}$/.test(encoded) || encoded.length > 8_400_000) throw new BridgeError('Screenshot is invalid or exceeds 6 MB.');
  const bytes = Buffer.from(encoded, 'base64');
  const png = bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (!png && !jpeg) throw new BridgeError('Screenshot must contain PNG or JPEG image data.');
  if (bytes.length > 6_000_000) throw new BridgeError('Screenshot exceeds 6 MB.');
  return { bytes, extension: png ? 'png' : 'jpg' };
}

export class PhoneAgent {
  constructor({ codex, model = process.env.TANGO_MODEL || 'gpt-6.1-sol', cwd, maxSessions = 8, idleMs = 30 * 60_000 }) {
    this.codex = codex; this.model = model; this.cwd = cwd; this.maxSessions = maxSessions; this.idleMs = idleMs;
    this.sessions = new Map(); this.pendingLogin = null; this.loginError = null;
    codex.on?.('disconnected', () => { this.sessions.clear(); this.pendingLogin = null; });
    codex.on?.('notification', message => {
      if (message.method === 'account/login/completed') {
        this.pendingLogin = null;
        this.loginError = message.params?.success ? null : 'ChatGPT sign-in did not complete. Start sign-in again.';
      }
    });
  }

  async status() {
    return { ...(await this.codex.accountStatus()), model: this.model, ...(this.loginError ? { message: this.loginError } : {}) };
  }

  async authStart(mode = 'device') {
    const status = await this.status();
    if (status.authenticated) return { authenticated: true, message: 'Already signed in with ChatGPT.' };
    if (!['device', 'browser'].includes(mode)) throw new BridgeError('Auth mode must be device or browser.');
    if (this.pendingLogin) return this.pendingLogin;
    this.loginError = null;
    const result = await this.codex.startLogin(mode);
    this.pendingLogin = {
      ...(result.authUrl ? { authUrl: result.authUrl } : {}),
      ...(result.userCode ? { userCode: result.userCode } : {}),
      ...(result.verificationUrl ? { verificationUrl: result.verificationUrl } : {}),
      message: result.userCode ? 'Open the verification link and enter this one-time code. Then return to Tango.' : 'Open this sign-in link on the computer running the bridge. Then return to Tango.',
    };
    return this.pendingLogin;
  }

  async step(body, { signal } = {}) {
    validateStep(body);
    const screenshot = decodeScreenshot(body.observation);
    const status = await this.status();
    if (!status.authenticated) throw new BridgeError('Sign in with ChatGPT in Tango before running the phone agent.', 401, 'chatgpt_sign_in_required');
    await this.prune();
    const model = body.model || this.model;
    let session = this.sessions.get(body.sessionId);
    if (session?.busy) throw new BridgeError('A step is already running for this session.', 409, 'session_busy');
    if (session && session.model !== model) {
      session.busy = true;
      await this.codex.closeThread(session.threadId);
      if (this.sessions.get(body.sessionId) === session) this.sessions.delete(body.sessionId);
      session = null;
    }
    // The Android harness uses a new session ID for a new task and updates the
    // trusted goal when the human answers or steers the current task. Preserve
    // verified facts and the model's context across those updates.
    if (session) session.goal = body.goal;
    if (!session) {
      if (this.sessions.size >= this.maxSessions) throw new BridgeError('Too many active phone sessions. End an older task or wait for it to expire.', 429, 'too_many_sessions');
      // Claim the slot before awaiting thread creation to prevent duplicate
      // thread creation if the phone retries the same request concurrently.
      session = { goal: body.goal, model, busy: true, lastUsed: Date.now(), threadId: null };
      this.sessions.set(body.sessionId, session);
      try {
        session.threadId = await this.codex.startThread({
          model, cwd: this.cwd, ephemeral: true, approvalPolicy: 'never', sandbox: 'read-only',
          environments: [], baseInstructions: PHONE_POLICY, developerInstructions: 'Only return validated phone actions. Never use host tools.',
          config: { 'web_search': 'disabled' },
        });
      } catch (error) { if (this.sessions.get(body.sessionId) === session) this.sessions.delete(body.sessionId); throw error; }
    }
    session.busy = true; session.lastUsed = Date.now();
    let directory;
    try {
      if (signal?.aborted || session.canceled || this.sessions.get(body.sessionId) !== session) throw new BridgeError('Request canceled.', 499, 'canceled');
      const input = [{ type: 'text', text: buildTurnText(body) }];
      if (screenshot) {
        directory = await mkdtemp(join(tmpdir(), 'tango-screen-'));
        const path = join(directory, `screen.${screenshot.extension}`);
        await writeFile(path, screenshot.bytes, { mode: 0o600 });
        input.push({ type: 'localImage', path });
      }
      const result = await this.codex.runTurn(session.threadId, {
        input, model, effort: 'low', summary: 'none', outputSchema: ACTION_SCHEMA,
        approvalPolicy: 'never', sandboxPolicy: { type: 'readOnly', networkAccess: false }, environments: [],
      }, { signal });
      const output = guardAction(parseAction(result.text, body.observation), body);
      if (result.usage) output.usage = result.usage;
      if (output.action.type === 'done') {
        await this.codex.closeThread(session.threadId);
        if (this.sessions.get(body.sessionId) === session) this.sessions.delete(body.sessionId);
      }
      return output;
    } catch (error) {
      // A failed turn may still be winding down. Starting a fresh ephemeral
      // conversation on retry avoids running concurrent turns against it.
      await this.codex.closeThread(session.threadId);
      if (this.sessions.get(body.sessionId) === session) this.sessions.delete(body.sessionId);
      throw error;
    } finally {
      session.busy = false; session.lastUsed = Date.now();
      if (directory) await rm(directory, { recursive: true, force: true });
    }
  }

  async cancel(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) session.canceled = true;
    if (session?.threadId) { await this.codex.interrupt(session.threadId); await this.codex.closeThread(session.threadId); }
    if (this.sessions.get(sessionId) === session) this.sessions.delete(sessionId);
    return { ok: true };
  }

  async prune() {
    for (const [id, session] of this.sessions) {
      if (!session.busy && Date.now() - session.lastUsed > this.idleMs) {
        await this.codex.closeThread(session.threadId);
        if (this.sessions.get(id) === session) this.sessions.delete(id);
      }
    }
  }
}
