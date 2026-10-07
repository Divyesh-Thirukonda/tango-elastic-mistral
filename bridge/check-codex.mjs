import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir, homedir } from 'node:os';
import { join } from 'node:path';
import { CodexAppServer } from './app-server.mjs';

// Only initialize and account/read. This never begins login or inference.
const directory = await mkdtemp(join(tmpdir(), 'tango-codex-check-'));
const home = process.argv.includes('--existing') ? process.env.CODEX_HOME || join(homedir(), '.codex') : join(directory, 'codex');
await mkdir(home, { recursive: true, mode: 0o700 });
const codex = new CodexAppServer({ executable: process.env.TANGO_CODEX_BIN || 'codex', codexHome: home, cwd: directory });
try {
  const status = await codex.accountStatus();
  console.log(JSON.stringify({ ok: true, provider: status.provider, authenticated: status.authenticated, accountType: status.accountType, planType: status.planType, mode: process.argv.includes('--existing') ? 'existing-read-only' : 'isolated' }));
} catch (error) {
  console.error(JSON.stringify({ ok: false, message: error.message })); process.exitCode = 1;
} finally { codex.close(); await rm(directory, { recursive: true, force: true }); }
