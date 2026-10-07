// Deterministic local provider for Android integration tests. No AI/API calls.
import http from 'node:http';
const token = 'tango-test-only-8788';
const state = { name: 'tango-fake-bridge', requests: 0, askRequests: 0, resumedRequests: 0,
  pausePending: 0, stopPending: 0, pauseDelivered: 0, stopDelivered: 0, observationPackages: [] };
const json = (response, body, status = 200) => {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
};
const server = http.createServer(async (request, response) => {
  if (request.url === '/status') return json(response, state);
  if (request.url !== '/v1/step' || request.method !== 'POST') return json(response, { error: 'Unknown route' }, 404);
  if (request.headers.authorization !== `Bearer ${token}`) return json(response, { error: 'Wrong test token' }, 401);
  let content = '';
  for await (const part of request) {
    content += part;
    if (content.length > 2_000_000) return json(response, { error: 'Body too large' }, 413);
  }
  let task;
  try { task = JSON.parse(content); } catch { return json(response, { error: 'Invalid JSON' }, 400); }
  state.requests++;
  state.observationPackages.push(task.observation?.packageName ?? '');
  const goal = task.goal ?? '';
  if (goal.includes('SMOKE_ASK_RESUME')) {
    if (goal.includes('Follow-up instruction from user: Board Game Club')) {
      state.resumedRequests++;
      return json(response, { action: { type: 'done', summary: 'Deterministic task completed for Board Game Club.', reason: 'Test reply observed.' } });
    }
    state.askRequests++;
    return json(response, { action: { type: 'ask', question: 'Which club name?', reason: 'Required test fact is missing.' } });
  }
  const kind = goal.includes('SMOKE_PAUSE_DELAY') ? 'pause' : goal.includes('SMOKE_STOP_DELAY') ? 'stop' : null;
  if (kind) {
    state[`${kind}Pending`]++;
    setTimeout(() => {
      state[`${kind}Delivered`]++;
      json(response, { action: { type: 'home', reason: 'This delayed action must be discarded after pause/stop.' } });
    }, 3500);
    return;
  }
  return json(response, { error: 'Unexpected smoke goal' }, 400);
});
server.listen(8788, '127.0.0.1', () => process.stdout.write('Deterministic test bridge listening on 127.0.0.1:8788\n'));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
