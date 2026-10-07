const nullableString = { type: ['string', 'null'] };
const nullableNumber = { type: ['number', 'null'] };

export const ACTION_TYPES = ['tap', 'type', 'swipe', 'scroll', 'back', 'home', 'launch', 'open_url', 'wait', 'ask', 'done'];
// All keys are required and nullable for strict Structured Outputs. The HTTP
// response omits nulls after validating the requirements of the chosen action.
export const ACTION_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['action'],
  properties: {
    action: {
      type: 'object', additionalProperties: false,
      required: ['type', 'target', 'text', 'x', 'y', 'x2', 'y2', 'durationMs', 'packageName', 'url', 'direction', 'reason', 'question', 'summary', 'risk', 'authorized'],
      properties: {
        type: { type: 'string', enum: ACTION_TYPES }, target: nullableString,
        text: nullableString, x: nullableNumber, y: nullableNumber, x2: nullableNumber, y2: nullableNumber,
        durationMs: { type: ['integer', 'null'] }, packageName: nullableString,
        url: nullableString, direction: { type: ['string', 'null'], enum: ['up', 'down', 'left', 'right', null] },
        reason: nullableString, question: nullableString, summary: nullableString,
        risk: { type: ['string', 'null'], enum: ['low', 'medium', 'high', null] },
        authorized: { type: ['boolean', 'null'] },
      },
    },
  },
};

export class BridgeError extends Error {
  constructor(message, status = 400, code = 'invalid_request') {
    super(message); this.status = status; this.code = code;
  }
}

export function parseAction(text, observation = {}) {
  if (typeof text !== 'string' || text.length > 100_000) throw new BridgeError('The model returned an invalid action.', 502, 'invalid_model_output');
  let parsed;
  try { parsed = JSON.parse(text.trim()); } catch { throw new BridgeError('The model did not return a JSON action.', 502, 'invalid_model_output'); }
  return validateAction(parsed, observation);
}

export function validateAction(value, observation = {}) {
  const invalid = (message) => { throw new BridgeError(message, 502, 'invalid_model_output'); };
  if (!value || typeof value !== 'object' || Array.isArray(value) || !value.action || Object.keys(value).some(k => k !== 'action')) invalid('The model must return one action object.');
  if (typeof value.action !== 'object' || Array.isArray(value.action)) invalid('Invalid action object.');
  const a = Object.fromEntries(Object.entries(value.action).filter(([, v]) => v != null));
  if (Object.keys(a).some(k => !Object.hasOwn(ACTION_SCHEMA.properties.action.properties, k))) invalid('Unknown action field.');
  if (!ACTION_TYPES.includes(a.type)) invalid('Unsupported action type.');
  for (const field of ['target', 'text', 'packageName', 'url', 'direction', 'reason', 'question', 'summary', 'risk']) {
    if (a[field] !== undefined && (typeof a[field] !== 'string' || a[field].length > (field === 'text' ? 12_000 : 3_000))) invalid(`Invalid ${field}.`);
  }
  if (a.authorized !== undefined && typeof a.authorized !== 'boolean') invalid('Invalid authorization flag.');
  if (a.risk !== undefined && !['low', 'medium', 'high'].includes(a.risk)) invalid('Invalid risk level.');
  for (const field of ['x', 'y', 'x2', 'y2']) {
    if (a[field] !== undefined && (!Number.isFinite(a[field]) || a[field] < 0 || a[field] > 1)) invalid('Coordinates must be normalized from 0 to 1.');
  }
  if (a.durationMs !== undefined && (!Number.isInteger(a.durationMs) || a.durationMs < 100 || a.durationMs > 15_000)) invalid('Duration must be from 100 to 15000 ms.');
  const requireString = field => { if (!a[field]?.trim()) invalid(`The ${a.type} action needs ${field}.`); };
  const requireTarget = () => {
    requireString('target');
    if (Array.isArray(observation.nodes) && !observation.nodes.some(node => String(node.id ?? node.target) === a.target)) invalid('The action target is absent from the current screen.');
  };
  if (a.type === 'tap') {
    if (a.target) requireTarget();
    else if (a.x === undefined || a.y === undefined) invalid('Tap needs a current target or x,y.');
  }
  if (a.type === 'type') { requireTarget(); requireString('text'); }
  if (a.type === 'scroll') { requireTarget(); if (!['up', 'down', 'left', 'right'].includes(a.direction)) invalid('Scroll needs a direction.'); }
  if (a.type === 'swipe' && ['x', 'y', 'x2', 'y2'].some(field => a[field] === undefined)) invalid('Swipe needs start and end coordinates.');
  if (a.type === 'wait' && a.durationMs === undefined) invalid('Wait needs durationMs.');
  if (a.type === 'launch') { requireString('packageName'); if (!/^[A-Za-z][\w]*(?:\.[A-Za-z][\w]*)+$/.test(a.packageName)) invalid('Invalid Android package name.'); }
  if (a.type === 'open_url') {
    requireString('url'); let url;
    try { url = new URL(a.url); } catch { invalid('Invalid URL.'); }
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) invalid('Only http(s) URLs without credentials are allowed.');
  }
  if (a.type === 'ask') requireString('question');
  if (a.type === 'done') requireString('summary');
  return { action: a };
}

export function validateStep(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BridgeError('Expected a JSON object.');
  if (typeof body.sessionId !== 'string' || !/^[\w:.-]{1,128}$/.test(body.sessionId)) throw new BridgeError('sessionId must be 1 to 128 letters, numbers, underscores, dots, colons or hyphens.');
  if (typeof body.goal !== 'string' || !body.goal.trim() || body.goal.length > 16_000) throw new BridgeError('goal must contain 1 to 16000 characters.');
  if (!body.observation || typeof body.observation !== 'object' || Array.isArray(body.observation)) throw new BridgeError('observation must be an object.');
  if (body.history !== undefined && (!Array.isArray(body.history) || body.history.length > 250)) throw new BridgeError('history must be an array with at most 250 entries.');
  if (body.userReply !== undefined && (typeof body.userReply !== 'string' || body.userReply.length > 16_000)) throw new BridgeError('userReply must be a short string.');
  if (body.model !== undefined && (typeof body.model !== 'string' || !/^[A-Za-z0-9][\w.-]{0,99}$/.test(body.model))) throw new BridgeError('Invalid model identifier.');
  if (JSON.stringify(body.observation.nodes ?? []).length > 400_000) throw new BridgeError('Screen tree is too large.');
  return body;
}

export function guardAction(result, body) {
  const a = result.action;
  const nodes = body.observation.nodes ?? [];
  const selected = nodes.filter(n => {
    if (a.target) return String(n.id ?? n.target) === a.target;
    if (a.type !== 'tap' || !Array.isArray(n.bounds) || n.bounds.length !== 4) return false;
    const x = a.x * (body.observation.width ?? body.observation.screenshotWidth ?? 0);
    const y = a.y * (body.observation.height ?? body.observation.screenshotHeight ?? 0);
    return x >= n.bounds[0] && x <= n.bounds[2] && y >= n.bounds[1] && y <= n.bounds[3];
  });
  const labels = selected.flatMap(node => [node.text, node.contentDescription, node.description].filter(v => typeof v === 'string'));
  const marker = 'follow-up instruction from user:';
  const latest = body.userReply ?? (body.goal.toLowerCase().includes(marker) ? body.goal.slice(body.goal.toLowerCase().lastIndexOf(marker) + marker.length) : '');
  const explicitlySend = /\bsend (it|the draft|that draft|the email|this email|the message)\b/i.test(latest) && !/\b(do not send|don['’]t send|never send)\b/i.test(latest);
  if (['type', 'tap'].includes(a.type) && selected.some(node => node.password || node.isPassword)) {
    return { action: { type: 'ask', question: 'Please sign in on your phone, then tell me to continue.', reason: 'A private sign-in field needs you.', risk: 'low' } };
  }
  if (a.type === 'tap' && !explicitlySend && /\b(draft|do not send|don['’]t send|keep it in my email)\b/i.test(body.goal) && labels.some(label => /^send(?:\s+(email|message))?(?:\s+button)?$/i.test(label.trim()))) {
    return { action: { type: 'ask', question: 'The email is ready as a draft. Should I continue with the remaining task?', reason: 'Your request says to keep the email as a draft.', risk: 'low' } };
  }
  return result;
}
