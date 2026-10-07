import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { createInterface } from 'node:readline';
import { BridgeError } from './actions.mjs';

export const CLIENT_INFO = { name: 'tango_android_bridge', title: 'Tango Android Bridge', version: '0.1.0' };
const HOST_TOOL_ITEMS = new Set(['hookPrompt', 'functionCallOutput', 'commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'collabAgentToolCall', 'subAgentActivity', 'webSearch', 'imageView', 'sleep', 'imageGeneration', 'enteredReviewMode', 'exitedReviewMode']);
export const CODEX_CONFIG = {
  'features.shell_tool': false,
  'features.unified_exec': false,
  'features.apps': false,
  'features.hooks': false,
  'features.multi_agent': false,
  'features.goals': false,
  'features.memories': false,
  'features.code_mode.enabled': false,
  'features.plugins': false,
  'features.remote_plugin': false,
  'features.tool_suggest': false,
  'features.skill_search': false,
  'features.browser_use': false,
  'features.browser_use_external': false,
  'features.computer_use': false,
  'features.view_image': false,
  'features.image_generation': false,
  'features.sleep_tool': false,
  'features.request_permissions_tool': false,
  'features.shell_snapshot': false,
  'allow_login_shell': false,
  'project_doc_max_bytes': 0,
  'web_search': 'disabled',
  'forced_login_method': 'chatgpt',
};

export function isolationOverrides(config) {
  // Empty CLI tables MERGE with inherited settings, rather than clear them.
  // Explicitly override every entry. Quote TOML inline-table keys (not dotted
  // CLI paths) so plugin/server IDs containing dots remain exact keys.
  const overrides = [];
  for (const [table, fields] of [['mcp_servers', 'enabled=false,required=false'], ['plugins', 'enabled=false']]) {
    const names = Object.keys(config?.[table] ?? {});
    if (names.length) overrides.push(`${table}={${names.map(name => `${JSON.stringify(name)}={${fields}}`).join(',')}}`);
  }
  return overrides;
}

export function assertIsolatedConfig(config) {
  const unsafeMcp = Object.values(config?.mcp_servers ?? {}).some(server => server.enabled !== false);
  const unsafePlugin = Object.values(config?.plugins ?? {}).some(plugin => plugin.enabled !== false);
  const unsafeFeatures = ['shell_tool', 'unified_exec', 'apps', 'hooks', 'multi_agent', 'plugins', 'remote_plugin', 'tool_suggest', 'skill_search', 'browser_use', 'browser_use_external', 'computer_use', 'view_image', 'image_generation', 'request_permissions_tool']
    .some(feature => config?.features?.[feature] !== false);
  if (unsafeMcp || unsafePlugin || unsafeFeatures || config?.features?.code_mode?.enabled !== false || config?.web_search !== 'disabled') {
    throw new BridgeError('Codex host tools could not be fully disabled. Use a fresh TANGO_CODEX_HOME and sign in there, or check managed Codex requirements.', 503, 'unsafe_codex_config');
  }
}

export function appServerArgs(extraOverrides = []) {
  const args = ['app-server', '--stdio'];
  for (const [key, value] of Object.entries(CODEX_CONFIG)) args.push('-c', `${key}=${JSON.stringify(value)}`);
  for (const override of extraOverrides) args.push('-c', override);
  return args;
}

export function safeChildEnvironment(codexHome, parent = process.env) {
  const env = { ...parent, CODEX_HOME: codexHome };
  // Native ChatGPT auth is the only provider. Never accidentally use a paid key
  // or inherit a host application's externally managed access token.
  for (const key of ['OPENAI_API_KEY', 'CODEX_API_KEY', 'ACCESS_TOKEN', 'CODEX_ACCESS_TOKEN']) delete env[key];
  return env;
}

export class CodexAppServer extends EventEmitter {
  constructor({ executable = 'codex', codexHome, cwd, requestTimeoutMs = 60_000, spawnProcess = spawn } = {}) {
    super(); this.executable = executable; this.codexHome = codexHome; this.cwd = cwd;
    this.requestTimeoutMs = requestTimeoutMs; this.spawnProcess = spawnProcess;
    this.pending = new Map(); this.collectors = new Map(); this.nextId = 1; this.child = null; this.startPromise = null;
  }

  async start() {
    if (this.startPromise) return this.startPromise;
    this.startPromise = this.#start().catch(error => { this.startPromise = null; throw error; });
    return this.startPromise;
  }

  async #start() {
    await this.#connect();
    const initial = await this.request('config/read', { includeLayers: false });
    const overrides = isolationOverrides(initial.config);
    if (overrides.length) {
      // Startup only read configuration; no thread or inference has started.
      // Restart this child with per-entry restrictions without writing config.
      const probe = this.child; this.child = null; probe.kill();
      await this.#connect(overrides);
    }
    const effective = overrides.length ? await this.request('config/read', { includeLayers: false }) : initial;
    try { assertIsolatedConfig(effective.config); }
    catch (error) { this.child?.kill(); throw error; }
  }

  async #connect(extraOverrides = []) {
    const child = this.spawnProcess(this.executable, appServerArgs(extraOverrides), {
      cwd: this.cwd, env: safeChildEnvironment(this.codexHome), stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    });
    this.child = child;
    child.stderr.on('data', () => {}); // Never emit native diagnostics with possible private data.
    child.stdin.on('error', () => this.#disconnect(new BridgeError('Codex disconnected. Retry or restart the bridge.', 503, 'codex_disconnected'), child));
    child.once('error', () => this.#disconnect(new BridgeError('Could not launch Codex. Install the Codex CLI and run the bridge again.', 503, 'codex_unavailable'), child));
    child.once('exit', () => this.#disconnect(new BridgeError('Codex disconnected. Retry or restart the bridge.', 503, 'codex_disconnected'), child));
    const lines = createInterface({ input: child.stdout });
    lines.on('line', line => {
      if (this.child !== child) return;
      if (line.length > 8_000_000) { child.kill(); return; }
      let message;
      try { message = JSON.parse(line); } catch { return; }
      try { this.handleMessage(message); } catch { child.kill(); }
    });
    await this.request('initialize', { clientInfo: CLIENT_INFO, capabilities: { experimentalApi: true } });
    this.notify('initialized', {});
  }

  #disconnect(error, child = this.child) {
    if (child !== this.child) return;
    this.child = null; this.startPromise = null;
    for (const request of this.pending.values()) { clearTimeout(request.timer); request.reject(error); }
    this.pending.clear();
    for (const collector of this.collectors.values()) collector.reject(error);
    this.collectors.clear();
    this.emit('disconnected');
  }

  write(message) {
    if (!this.child || this.child.stdin.destroyed) throw new BridgeError('Codex is unavailable.', 503, 'codex_unavailable');
    this.child.stdin.write(JSON.stringify(message) + '\n');
  }

  notify(method, params) { this.write({ method, params }); }

  request(method, params = {}, timeoutMs = this.requestTimeoutMs) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new BridgeError(`Codex ${method} timed out.`, 504, 'codex_timeout')); }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write({ id, method, params }); } catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }

  handleMessage(message) {
    if (message.id !== undefined && !message.method) {
      const request = this.pending.get(message.id);
      if (!request) return;
      this.pending.delete(message.id); clearTimeout(request.timer);
      if (message.error) request.reject(new BridgeError(`Codex rejected the request: ${String(message.error.message ?? 'protocol error').slice(0, 600)}`, 502, 'codex_error'));
      else request.resolve(message.result);
      return;
    }
    if (message.id !== undefined && message.method) {
      // The bridge does not grant the model host tools, credentials, or approvals.
      this.write({ id: message.id, error: { code: -32601, message: 'The bridge only executes validated phone actions. Host requests are unsupported.' } });
      return;
    }
    const p = message.params ?? {};
    const collector = this.collectors.get(p.threadId);
    if (collector && (!collector.turnId || !p.turnId || collector.turnId === p.turnId)) {
      if (message.method === 'turn/started') {
        collector.turnId = p.turn?.id ?? collector.turnId;
        if (collector.aborted && collector.turnId) this.request('turn/interrupt', { threadId: p.threadId, turnId: collector.turnId }, 10_000).catch(() => {});
      }
      if ((message.method === 'item/started' || message.method === 'item/completed') && HOST_TOOL_ITEMS.has(p.item?.type)) {
        collector.reject(new BridgeError('The model attempted an unsupported computer tool. No phone action was returned.', 502, 'host_tool_blocked'));
        if (collector.turnId) this.request('turn/interrupt', { threadId: p.threadId, turnId: collector.turnId }, 10_000).catch(() => {});
      }
      if (message.method === 'item/agentMessage/delta') {
        const id = p.itemId ?? 'reply';
        collector.deltas.set(id, (collector.deltas.get(id) ?? '') + (p.delta ?? ''));
      }
      if (message.method === 'item/completed' && p.item?.type === 'agentMessage') collector.messages.set(p.item.id, p.item);
      if (message.method === 'thread/tokenUsage/updated') collector.usage = p.tokenUsage?.last;
      if (message.method === 'turn/completed') {
        const turn = p.turn;
        if (turn?.status !== 'completed') collector.reject(new BridgeError(turn?.error?.message ? `Codex could not complete the action: ${String(turn.error.message).slice(0, 600)}` : 'Codex generation failed or was interrupted.', 502, 'inference_failed'));
        else {
          for (const item of turn.items ?? []) if (item.type === 'agentMessage') collector.messages.set(item.id, item);
          const messages = [...collector.messages.values()];
          const final = messages.findLast?.(item => item.phase === 'final_answer') ?? messages.at(-1);
          const text = final?.text ?? [...collector.deltas.values()].at(-1) ?? '';
          collector.resolve({ text, usage: collector.usage });
        }
      }
    }
    this.emit('notification', message);
  }

  async accountStatus() {
    await this.start();
    const result = await this.request('account/read', { refreshToken: false });
    return {
      authenticated: result?.account?.type === 'chatgpt',
      provider: 'codex-chatgpt', accountType: result?.account?.type ?? null,
      planType: result?.account?.planType ?? null,
    };
  }

  async startLogin(mode = 'device') {
    await this.start();
    return this.request('account/login/start', mode === 'browser'
      ? { type: 'chatgpt', useHostedLoginSuccessPage: true, appBrand: 'chatgpt' }
      : { type: 'chatgptDeviceCode' });
  }

  async startThread(params) {
    await this.start();
    const result = await this.request('thread/start', params);
    if (!result?.thread?.id) throw new BridgeError('Codex did not create a conversation.', 502, 'codex_error');
    return result.thread.id;
  }

  async runTurn(threadId, params, { timeoutMs = 120_000, signal } = {}) {
    if (this.collectors.has(threadId)) throw new BridgeError('This phone session already has a running step.', 409, 'session_busy');
    if (signal?.aborted) throw new BridgeError('Request canceled.', 499, 'canceled');
    let collector;
    let timer;
    const abort = () => {
      collector.aborted = true;
      if (collector?.turnId) this.request('turn/interrupt', { threadId, turnId: collector.turnId }, 10_000).catch(() => {});
      collector.reject(new BridgeError('Request canceled.', 499, 'canceled'));
    };
    const completion = new Promise((resolve, reject) => {
      collector = { turnId: null, deltas: new Map(), messages: new Map(), resolve, reject };
      this.collectors.set(threadId, collector);
      timer = setTimeout(() => {
        if (collector.turnId) this.request('turn/interrupt', { threadId, turnId: collector.turnId }, 10_000).catch(() => {});
        reject(new BridgeError('The AI step timed out. Your phone can retry or ask you how to proceed.', 504, 'inference_timeout'));
      }, timeoutMs);
      signal?.addEventListener('abort', abort, { once: true });
    });
    // Register the collector before turn/start so fast streamed completions
    // cannot race the RPC response. Mark the promise handled while awaiting it.
    completion.catch(() => {});
    try {
      const response = await this.request('turn/start', { threadId, ...params });
      collector.turnId = response?.turn?.id;
      if (signal?.aborted) abort();
      return await completion;
    } catch (error) {
      if (collector.turnId) await this.request('turn/interrupt', { threadId, turnId: collector.turnId }, 10_000).catch(() => {});
      throw error;
    } finally {
      clearTimeout(timer); signal?.removeEventListener('abort', abort);
      this.collectors.delete(threadId);
    }
  }

  async interrupt(threadId) {
    const collector = this.collectors.get(threadId);
    if (collector?.turnId) await this.request('turn/interrupt', { threadId, turnId: collector.turnId }, 10_000);
  }

  async closeThread(threadId) {
    if (this.child) await this.request('thread/unsubscribe', { threadId }, 10_000).catch(() => {});
  }

  close() { this.child?.kill(); }
}
