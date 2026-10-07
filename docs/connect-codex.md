# Connect Codex to Tango

Tango runs on the phone using its own ChatGPT sign-in. The optional **Connect to Codex** control lets a Codex session on your computer send Tango tasks, inspect thread journals, see the phone, and steer, answer, pause or stop tasks. The phone keeps its normal account, cancellation and security checks.

1. Connect the Android phone with USB debugging enabled and authorize your computer on the phone.
2. In Tango, sign in with ChatGPT, enable phone control, and choose **Connect to Codex** in the sidebar or Account. Tap **Connect**. The sheet shows connection status and **Copy setup**; open **Connection details** to view the token and forwarding command.
3. From this project folder, forward the phone’s loopback port:

   ```sh
   adb forward tcp:8766 tcp:8766
   ```

   If multiple Android devices are connected, use `adb -s DEVICE_SERIAL forward tcp:8766 tcp:8766`.

4. Register the bundled MCP client with Codex, replacing both placeholders:

   ```sh
   codex mcp add tango --env TANGO_CONNECTION_TOKEN=PHONE_TOKEN -- node /absolute/path/to/Tango/scripts/codex-tango.mjs mcp
   ```

   The **Copy setup** control on the phone supplies the token and command; replace its project path with your checkout location. Start a fresh Codex chat if Tango tools do not appear in the current chat. Close the connection sheet with **Close**, ×, or Back when finished; closing it leaves the connection available.

You can now ask Codex to test a mobile app using Tango. For example: “Use Tango to open my Android app, check sign-in navigation and report the result. Use the test account I provide; stop before any purchase or real submission.” Codex can observe the screen, send a scoped prompt, check the resulting thread and adjust its instructions. Phone observations do not use model inference; tasks run through Tango’s signed-in ChatGPT access and use that account’s available usage.

The MCP tools are `tango_status`, `tango_threads`, `tango_thread`, `tango_observe`, `tango_prompt`, `tango_steer`, `tango_respond`, `tango_pause` and `tango_stop`. Observations include a native MCP screenshot plus accessibility data when Android permits it. Password, lock and secure screens keep the phone’s existing protections. Tango executes phone actions through its existing agent; this connector does not create a second concurrent action loop.

To use the client directly without MCP:

```sh
export TANGO_CONNECTION_TOKEN=PHONE_TOKEN
node scripts/codex-tango.mjs status
node scripts/codex-tango.mjs prompt 'Open the app and check the onboarding flow'
node scripts/codex-tango.mjs thread THREAD_ID
node scripts/codex-tango.mjs observe
node scripts/codex-tango.mjs steer THREAD_ID 'Now check the Settings screen'
node scripts/codex-tango.mjs respond THREAD_ID 'I completed the sign-in; continue'
node scripts/codex-tango.mjs pause THREAD_ID
node scripts/codex-tango.mjs stop THREAD_ID
```

`prompt` starts a new thread by default. Supply a thread ID as the next argument to continue it, and optionally `instant`, `medium` or `high` after that. Another running thread must be steered or paused before starting a new one. `respond`, `steer`, `pause` and `stop` require the active thread’s ID so a stale client cannot control another thread.

Choose **Connect to Codex → Disconnect** in Tango or use **Disconnect** in the persistent connection notification to revoke access. The token stays only in the phone process’s memory; disconnecting, restarting Tango, or changing the signed-in account requires reconnecting and updating the computer’s token. The listener binds only to `127.0.0.1` on the phone, requires the token on every request, and rejects browser origins. No LAN listener or account-credential sharing is involved.

If a connection fails, check `adb devices`, re-run the forwarding command, confirm Tango still shows connected, and update the current token. The computer endpoint defaults to `http://127.0.0.1:8766`; a different forwarded local port can be specified with `TANGO_CONNECTION_URL=http://127.0.0.1:PORT`.

The phone API uses authenticated JSON requests:

| Method and path | Request or result |
|---|---|
| `GET /v1/status` | Active thread ID, state, progress, question and mode |
| `GET /v1/threads` | Current account’s non-archived thread summaries |
| `GET /v1/threads/THREAD_ID` | Thread details and journal |
| `GET /v1/observe` | Accessibility observation and screenshot when available |
| `POST /v1/prompts` | `{ "prompt": "…", "threadId": "optional", "mode": "optional" }` |
| `POST /v1/steer` | `{ "threadId": "…", "reply": "…" }` |
| `POST /v1/respond` | `{ "threadId": "…", "reply": "…" }` |
| `POST /v1/pause` | `{ "threadId": "…" }` |
| `POST /v1/stop` | `{ "threadId": "…" }` |

Every request requires `Authorization: Bearer TOKEN`. POST requests require `Content-Type: application/json` and `Content-Length`; bodies are limited to 64 KiB and prompt text to 16,000 characters. Successful task controls return `{ "accepted": true, "threadId": "…" }`; check status or the thread journal for execution results. Errors use `{ "error": { "code": "…", "message": "…" } }`.

Offline connector checks (no live inference):

```sh
node --test scripts/test/codex-tango.test.mjs
scripts/codex-connection-test.sh
scripts/codex-connection-smoke.sh
```
