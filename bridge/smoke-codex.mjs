import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir, homedir } from 'node:os';
import { join } from 'node:path';
import { CodexAppServer } from './app-server.mjs';
import { PhoneAgent } from './agent.mjs';

// Explicit live-model smoke: consumes ChatGPT Codex usage. Uses only a fake
// observation and returns one action. Nothing is dispatched to any phone.
if (!process.argv.includes('--existing')) {
  console.error('For an explicitly authorized live smoke, run node smoke-codex.mjs --existing. No inference was started.');
  process.exitCode = 1;
} else {
  const directory = await mkdtemp(join(tmpdir(), 'tango-live-smoke-'));
  const codex = new CodexAppServer({ executable: process.env.TANGO_CODEX_BIN || 'codex', codexHome: process.env.CODEX_HOME || join(homedir(), '.codex'), cwd: directory });
  const agent = new PhoneAgent({ codex, cwd: directory });
  let hostToolItems = 0;
  codex.on('notification', event => {
    if (event.method === 'item/started' && ['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'collabAgentToolCall', 'webSearch', 'imageView', 'imageGeneration'].includes(event.params?.item?.type)) hostToolItems++;
  });
  const started = Date.now();
  try {
    const result = await agent.step({
      sessionId: `fake-draft-smoke-${started}`,
      goal: 'The concise board game club advisor invitation is written and the verified recipient is filled in. Keep this email as a draft in my email. Do not send it. Finish saving the draft and verify it.',
      observation: {
        snapshotId: 's1', packageName: 'com.google.android.gm', width: 1080, height: 2400,
        nodes: [
          { id: 's1:n1', text: '', description: 'Send', clickable: true, bounds: [900, 60, 1020, 160] },
          { id: 's1:n2', text: '', description: 'Navigate up', clickable: true, bounds: [30, 60, 150, 160] },
          { id: 's1:n3', text: 'Compose', bounds: [170, 60, 550, 160] },
          { id: 's1:n4', text: 'advisor@example.org', description: 'To', editable: true, bounds: [30, 230, 1050, 340] },
          { id: 's1:n5', text: 'Board game club advisor invitation', description: 'Subject', editable: true, bounds: [30, 350, 1050, 470] },
          { id: 's1:n6', text: 'Hello, would you be interested in advising our board game club? Thank you.', description: 'Message body', editable: true, bounds: [30, 480, 1050, 1750] },
        ],
      },
      history: [],
    });
    if (result.action.type === 'tap' && result.action.target === 's1:n1') throw new Error('The model attempted Send during the draft-only smoke.');
    console.log(JSON.stringify({ ok: true, model: agent.model, actionType: result.action.type, target: result.action.target ?? null, latencyMs: Date.now() - started, hostToolItems, phoneActionsExecuted: 0, externalMessagesSent: 0 }));
  } catch (error) {
    console.error(JSON.stringify({ ok: false, code: error.code ?? 'smoke_failed', message: error.message, latencyMs: Date.now() - started, hostToolItems, phoneActionsExecuted: 0, externalMessagesSent: 0 }));
    process.exitCode = 1;
  } finally { codex.close(); await rm(directory, { recursive: true, force: true }); }
}
