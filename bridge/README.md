# Tango Android companion

This local Node.js bridge runs the installed **Codex CLI app-server**. Its native ChatGPT device-code sign-in supplies AI access; Tango does not copy a desktop auth cache, collect an API key, borrow an OAuth client ID, or handle OpenAI access tokens. Codex owns sign-in, storage and renewal. A compatible ChatGPT account and its available Codex usage are required. There is no backend hosting fee or npm dependency.

The Android APK still needs this companion computer running. It is not a standalone phone-only model runtime.

## Run

Install Node.js 20 or newer and the official Codex CLI, with `codex` on PATH. From this directory:

```sh
npm test
node server.mjs --lan
```

The bridge prints a phone URL and a pairing token. Put both into Tango's settings. Keep your phone and computer on the same **trusted Wi-Fi**, keep this terminal open, and allow inbound port 8787 if your computer's firewall asks. Tap **Sign in with ChatGPT** in Tango, open the verification URL, enter the shown one-time code yourself, and return to Tango. Device-code login may need to be enabled in ChatGPT's security settings. The phone uses its normal browser for the sign-in ceremony; the bridge stores the resulting native Codex login in its separate state directory.

Without `--lan`, the bridge listens on `127.0.0.1`, suitable for USB testing with `adb reverse tcp:8787 tcp:8787` and a phone bridge URL of `http://127.0.0.1:8787`.

HTTP on a local network is not encrypted. Use USB or a trusted private network. Do not expose this port to the public internet. All endpoints, including health and authentication, require `Authorization: Bearer <pairing token>`. Browser origins are rejected and no permissive CORS is enabled. Treat the printed pairing token as a password.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `TANGO_PORT` | `8787` | HTTP port |
| `TANGO_HOST` | `127.0.0.1`, or `0.0.0.0` with `--lan` | Listening address |
| `TANGO_MODEL` | `gpt-6.1-sol` | An available Codex model |
| `TANGO_CODEX_BIN` | `codex` | Installed CLI executable |
| `TANGO_STATE_DIR` | `bridge/.state` | Private bridge state |
| `TANGO_CODEX_HOME` | `bridge/.state/codex` | Native Codex auth/config directory |
| `TANGO_PAIRING_TOKEN` | Random, persisted in `.state/pairing-token` | Optional 32–256 character base64url-style token |

The default is a fresh, separate Codex home. It does not sign out, change or import the computer's existing Codex account. If you explicitly select your existing Codex home, its native account is used. Before creating any phone thread, the bridge reads effective configuration and explicitly disables every inherited MCP server and plugin in a restarted child process, without modifying config files. It also disables and verifies shell, apps, hooks, plugins, browser/computer control, image-view/generation tools and web search. Failure to enforce these restrictions blocks inference. Unexpected native host-tool items interrupt the model and return an error. Phone conversations are ephemeral. The bridge's working directory is empty, Android actions are the only intended output, and thread/turn environment access is disabled. A read-only, network-disabled tool sandbox further limits host actions. The Codex process itself necessarily has network access for model inference and authentication.

This prototype has not undergone a security audit. Policy text and screen heuristics cannot guarantee perfect decisions. The Android side must independently enforce its execution policy and show the user what it is doing.

## HTTP contract

All requests require the pairing bearer token. POST requests use `Content-Type: application/json`. Errors have the shape `{ "error": { "code": "...", "message": "..." } }`.

| Endpoint | Result |
|---|---|
| `GET /health` | `{ok, authenticated, provider, model}` |
| `GET /auth/status` | `{authenticated, provider, model, accountType, planType, message?}` |
| `POST /auth/start` | Body `{}` or `{mode:"device"}`; result `{verificationUrl?, userCode?, authUrl?, message}` |
| `POST /auth/start` | Body `{mode:"browser"}` starts the native computer-browser flow; open that URL on the companion computer because its callback is localhost |
| `POST /v1/step` | One validated Android action, optional native token usage |
| `POST /v1/cancel` | Body `{sessionId}`; interrupts that task and returns `{ok:true}` |

Example step body:

```json
{
  "sessionId": "task-1",
  "goal": "Find my course TA and save a concise advisor invitation as a draft.",
  "observation": {
    "packageName": "com.google.android.gm",
    "nodes": [{"id":"s1:n12","text":"Search in mail","editable":true}],
    "screenshotBase64": "...base64 PNG or JPEG...",
    "screenshotWidth": 1080,
    "screenshotHeight": 2400
  },
  "history": [],
  "userReply": "Optional answer to the agent's last question"
}
```

Screenshots may use `screenshot` or `screenshotBase64`. The bridge accepts raw base64 or a PNG/JPEG data URL, writes a private temporary image for Codex's `localImage` input, and removes it after the turn. It does not log phone observations, action text or screenshots. Images and tree data are transmitted through Codex to the model provider to choose an action. This is not an on-device inference system.

An action contains `type` plus the fields needed for that action, optional concise `reason`, `risk` (`low`, `medium`, `high`) and `authorized` (boolean):

```json
{"action":{"type":"tap","target":"s1:n12","reason":"Open email search.","risk":"low"}}
```

Supported actions:

- `tap`: `target` string from the current snapshot, or normalized `x,y` for visible canvas controls
- `type`: current `target` string and `text`; replaces a field's existing value
- `swipe`: normalized `x,y,x2,y2`, optional `durationMs`
- `scroll`: current `target` string and `direction` (`up`, `down`, `left`, `right`)
- `back`, `home`
- `launch`: Android `packageName`
- `open_url`: `url` using HTTP or HTTPS
- `wait`: `durationMs` from 100 to 15000
- `ask`: a specific `question`
- `done`: verified completion `summary`, including any unfinished part

Only current snapshot node IDs are valid. Coordinates are in `[0,1]`. Every response is parsed and validated before being returned. A node marked `password` or `isPassword` is never filled. Tapping an accessible Send node is intercepted when the goal specifies a draft. The human completes MFA, passwords, lock screens and CAPTCHAs privately. The policy instructs the model to execute authorized submission requests, preserve drafts, research current facts, avoid fabricating club registration answers, recover from temporary hangs and ask when useful recovery fails.

Session conversations stay in memory, expire after 30 idle minutes and are unsubscribed when complete or canceled. Human replies and steering updates preserve the same task's conversation and verified facts; a new task uses a new session ID. Restarting the bridge loses its model conversation; the phone's supplied goal and recent history can re-establish the task. The default permits eight simultaneous session IDs and one step per session. A model step has a two-minute deadline. Rate limits and subscription availability still apply. No API-key fallback is present in this bridge, and funding/access-token environment variables are removed from its child process.

## Verification and limits

```sh
npm test
npm run check:codex
node check-codex.mjs --existing
```

`check:codex` only initializes a fresh app-server and reads account status. `--existing` reads the normal account status without beginning login or inference. Development verified the installed CLI version `0.159.0-alpha.12.1`: native initialize/account-read succeed, and an isolated ephemeral thread can be created with the chosen protocol and restrictions. No account mutation or inference was used for those checks. The 27 deterministic tests use fake model output and cover authentication, target/coordinate/schema rejection, private screenshot cleanup, sign-in behavior, cancellation protocol, trusted follow-ups, inherited host-tool restrictions and streamed-event races.

An explicitly authorized live smoke on October 2, 2026 used native existing ChatGPT auth after effective host-tool restrictions were verified: `gpt-6.1-sol` returned a tap on the synthetic Gmail compose screen's Navigate up node in 8.5 seconds to save a draft. The complete generation/structured-output/validation path succeeded; no host tools, phone actions or external messages ran. This consumed ChatGPT Codex quota and used no API key or API funding. To reproduce this **live quota-consuming** test, explicitly run `node smoke-codex.mjs --existing`. It always uses a synthetic observation and never executes the returned action.

An installed, signed-in physical Android device is still needed for end-to-end testing. Cornell's actual TA, email and form details are researched at runtime from the phone; they are not hardcoded. This observation/action loop is too slow to guarantee successful real-time Clash Royale play. Accessibility trees and screenshots vary by app, and secure screens can be inaccessible. The app-server API is evolving, so a future CLI release may require protocol updates.

Official protocol and authentication references: [Codex app-server](https://learn.chatgpt.com/docs/app-server), [Codex authentication](https://learn.chatgpt.com/docs/auth), [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).
