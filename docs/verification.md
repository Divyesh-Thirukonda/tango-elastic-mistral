# Verification — October 7, 2026

## Current Android release: Tango 0.6.1

Fixed lost clarification context: the service previously cleared the pending question and turned a blank Continue into a generic claim that the blocker had been handled. Questions are now saved with their kind/evidence and paired with the actual reply. These pairs are included in subsequent model requests and survive service restarts. Older saved conversations migrate their visible pending question when continued.

The policy now prefers a clearly supported contextual interpretation, including affectionate contact names/emojis plus conversation evidence, over asking for absolute certainty. Continue accepts a single proposed candidate within the original request. Before exposing a non-security model question, Tango requests an internal second look with the proposed question, fresh observation, and earlier answers. Truly missing required facts, unresolved competing candidates, and security steps can still require input. A model question alone never causes an automatic send; the model must choose and verify the authorized phone actions.

Build/lint passed for **0.6.1/code 11** with **0 errors and 39 warnings**. Full native regression passed **275 result groups** (264 Boolean assertions plus counters/timing reports), using **159 intercepted HTTPS requests** and no live inference. New fixtures cover contextual reconsideration without a visible question, blank Continue paired with a proposed contact across a service restart, a reworded confirmation resolved without another visible handoff, and migration from the old saved-question format. Existing required-fact, security, draft-only, cancellation, thread/account, UI, attachment and voice checks still pass. [Full results](../dist/auth-smoke-0.6.1.txt), [clarification/recovery assertions](../dist/clarification-smoke-0.6.1.txt).

The inference/model fixtures passed **70 + 37 checks**, and the Codex connection passed **11 native integration groups** on this APK. These are deterministic protocol/service tests. Live model interpretation of actual WhatsApp contacts and physical-phone execution were not tested.

## Earlier Android release: Tango 0.6.0

Tango now uses shared press feedback, brief icon/status transitions, animated message arrivals, and interruptible drawer, Account, and in-app sheet transitions. Android's animation and haptic preferences remain authoritative. Account starts with the essential controls; Voice & shortcuts and Advanced expand when requested. Chat rows, the sidebar, and composer overlays have visible alternatives to long-press and swipe gestures.

Task details, account selection, setup, search, rename, connection details, errors, and confirmations use rounded in-app sheets. Close, Back, outside taps, and the drag handle dismiss them; destructive confirmations offer Cancel. Long content scrolls independently of sheet actions, including when the keyboard is open. Accessibility hides the underlying screen while a sheet is modal and restores it on dismissal.

Draft changes are staged immediately and written by an application-scoped worker, with a durable flush at lifecycle boundaries. Account disclosures, scroll position, search, unfinished rename text/cursor, and the selected chat survive restoration. Returning through the launcher preserves unfinished work. Backgrounding stops recording and cancels voice countdowns, queued drawer navigation, and pending sheet actions. Attachment copies can finish across Activity interruptions; cancel keeps already committed files. A process restart reports interrupted imports and cleans up uncommitted copies without resending. File commits use the captured account and thread.

Build and lint passed for **0.6.0/code 10**, with **0 errors and 39 warnings**. The focused native UI suite passed **83 groups** with animations enabled. Tests exercised press/cancel/disabled states, intermediate Account motion, finger-tracked drawer gestures, progressive disclosure, screen-reader modal isolation, keyboard escape controls, launcher return, durable drafts, and cancellable imports. [Focused UI results](../dist/ui-interaction-smoke-0.6.0.txt). The same **83 groups** passed with Android animations disabled, including immediate drawer settling and preserved controls/escape hatches. [Reduced-motion results](../dist/ui-reduced-motion-0.6.0.txt).

The full native regression on the final APK passed **271 groups** (260 Boolean assertions plus request counters/timing reports), using **144 intercepted HTTPS requests** and no live requests. This includes OAuth/account isolation, provider transport, autonomy, thread persistence, attachments, voice, and **83 UI/interruption groups**. [Full native results](../dist/auth-smoke-0.6.0.txt), [UI assertions extracted from that run](../dist/ui-smoke-0.6.0.txt).

The model and inference fixtures passed **37 + 70 checks**. Codex compatibility passed **11 native integration groups**, **7 Node tests**, and **16 HTTP parser checks**. No live inference or real-account sign-in was used. [Codex integration results](../dist/codex-connection-smoke.txt).

Screenshots inspected: [Account](../dist/tango-ui-account.png), [expanded Advanced](../dist/tango-ui-account-advanced.png), [task details sheet](../dist/tango-ui-task-details.png), [sidebar](../dist/tango-ui-sidebar.png), and [keyboard](../dist/tango-ui-keyboard.png). Verification uses the project Android 35 emulator. Physical S23 haptic feel, microphone/camera behavior, real-provider file interruption, and real-account inference still need device testing.

## Earlier Android release: Tango 0.5.1

The composer plus opens an in-app Camera / Photos / Files menu. Native pickers support multiple documents/photos; camera uses a temporary, narrowly granted content URI for full-resolution capture. Thumbnails and file cards persist in the original account/chat draft, can be removed, and appear on sent messages. JPEG/PNG/WebP images, PDFs, and UTF-8 text are included as actual model inputs, with private storage limits and cleanup on remove/delete/clear. A changed account or chat rejects a pending import.

The effort dial now opens a custom-drawn blue capsule directly over the conversation. Three detents select Instant / Medium / High immediately, with a white thumb, colored headline, animated snapping, haptics, and accessible range actions. No system slider dialog or confirmation button is used.

Rightward swipes across the blank conversation drag the sidebar into view. Left swipes close it, velocity controls snapping, and grabbing an in-flight animation preserves its visible position. Vertical scrolling, selected message text, editor gestures and picker drags retain their own input. Chat and Account selections close the panel smoothly; Account opens/returns with a short slide/fade. Initial toolbar/composer and Account layouts respect Android system bars.

The microphone state and drawable now update together before SpeechRecognizer startup. One stop tap enters transcription immediately; late readiness/results, canceled on-device fallback, permission resume and duplicate auto-listen launches cannot revive an old recording session. Speech tests use synthetic callbacks without recording audio.

The final native regression passed **228 groups**, including **51 UI groups**, using **144 intercepted HTTPS requests** and no live requests. Coverage includes actual PNG/PDF bytes and text in the Responses payload, draft/thread/account isolation, camera URI grants and cleanup, attachment previews and removal, real center-screen swipes, interrupted animations, scrolling/selection exclusions, immediate model persistence, system/keyboard insets, and speech callback races. [Full native results](../dist/auth-smoke-0.5.1.txt), [UI assertions extracted from that run](../dist/ui-smoke-0.5.1.txt), [isolated voice controls](../dist/voice-controls-smoke.txt).

Build and lint passed with **0 errors and 33 warnings** for **0.5.1/code 9**. The model/inference fixtures passed **37 + 70 checks**. Codex compatibility passed **11 native integration groups**, **7 Node tests**, and **16 HTTP parser checks** against this update. Screenshots were inspected: [home](../dist/tango-ui-idle.png), [effort picker](../dist/tango-ui-model.png), [attachment menu](../dist/tango-ui-attachments-menu.png), [sidebar](../dist/tango-ui-sidebar.png), [keyboard](../dist/tango-ui-keyboard.png). Tests used the project Android 35 emulator; physical S23 microphone/camera behavior and real-account attachment inference still require device testing. No live inference or paid service was used.

## Earlier Android release: Tango 0.5.0

Tango now has a ChatGPT-style conversation screen with a bottom composer, circular menu/account controls, a slide-out thread sidebar, search, new chats, and long-press rename/archive/delete. ChatGPT accounts have separate local conversations, drafts, messages, results and action history. Completed conversations accept follow-ups with the existing reasoning context. Switching conversations invalidates outstanding actions before restoring the selected thread paused. Task generation tickets remain unique across service recreation. The old single-task file migrates once to its owning account.

The composer exposes a discrete **Instant / Medium / High** slider. It persists the selected mode and chooses only account-advertised models and valid advertised Responses reasoning efforts; missing metadata leaves the provider default intact. **37 model-mode fixture checks** and the existing **70 inference parser checks** passed with no live inference.

The optional **Connect to Codex** flow exposes a phone loopback listener through USB ADB forwarding. Every request requires a revocable account-bound token. The bundled CLI/stdio MCP client supports prompts, follow-ups, replies, thread journals, phone observations and pause/stop. Sending a remote prompt hands Tango's foreground back to the phone apps while preserving the outgoing draft. Disconnect, account changes and process restart revoke the token. The current APK still performs normal tasks directly from the phone.

The native regression suite passed **194 groups**, including account/session, provider transport, recovery, task generations, thread persistence/isolation/migration, completed-task follow-ups, saved drafts, slider accessibility, single-composer replies and voice behavior. It used **144 intercepted HTTPS requests** and no live OpenAI requests. UI fixtures show the real keyboard and assert that the send control remains above it. [Native regression results](../dist/auth-smoke-0.5.0.txt).

Connector verification passed **7 Node client/MCP tests**, **16 Java HTTP parser checks** and **11 native Android integration groups**. The Android checks use the real loopback socket and fake Responses output to exercise foreground handoff, durable journals, draft preservation, remote task controls, stale/running-thread rejection, late-action cancellation, token rotation, account revocation and listener shutdown. Status/thread/observation reads use zero inference. [Connector integration results](../dist/codex-connection-smoke.txt), [USB/Codex setup](connect-codex.md).

The build and lint check pass with **0 errors and 27 warnings**. APK version is **0.5.0/code 8**, using the existing development signing key. Screenshots were visually inspected: [home](../dist/tango-ui-idle.png), [sidebar](../dist/tango-ui-sidebar.png), [model slider](../dist/tango-ui-model.png), [keyboard](../dist/tango-ui-keyboard.png). Native tests ran on the project Android 35 emulator; physical S23 behavior and pairing a real desktop Codex session still need device testing. No live inference quota or paid API requests were used.


## Earlier Android release: Tango 0.4.1

The Speak and Run buttons now display a microphone and send icon. Listening displays a stop icon until the speech callback resets it. The send control keeps its Run/Update behavior. Touch targets remain 52 dp high; TalkBack labels and long-press hints identify each action. Updated manual-voice guidance says “Tap send.”

Build and lint passed with **0 errors, 9 warnings**; **178 native checks passed** against version 0.4.1/code 7. The existing UI and voice checks were rerun, and the idle screen was visually inspected. [Updated home](../dist/tango-ui-idle.png). The APK retains its previous signing certificate. Fast I and the recovery/authentication harness also passed the existing suite; [current fixture results](../dist/fast-benchmark.json) use simulated provider latency. No live inference or paid service was used.

## Earlier Android release: Tango 0.4.0

Verified October 4, 2026. Account now has a single optional **Fast mode I** checkbox, off by default. A completed OpenAI response contains one current action and up to three future tap/type/scroll steps. Each queued step requires a matching package, unique screen marker and unique enabled semantic target in a fresh accessibility snapshot; IDs are rebound, not reused. Plans expire after 45 seconds and are discarded on failure or unexpected screens. Completion still requires a fresh AI observation. Queued actions cannot run after Stop, Pause, foreground inspection or an account change. Consequential taps such as Send, Submit, Save, Confirm and permissions are excluded from the local queue and require replanning.

Fast II is a tested development experiment: the same account-advertised OpenAI model uses Structured Outputs to return a current-screen candidate index or a normal action for variable parameters. No separate OpenAI decision-only model/API was identified. [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) supports constrained response formats, not an assertion that an action is correct. The production checkbox selects Fast I; Fast II is retained in source for reproducible comparisons, without another settings control.

Four alternating matched runs per mode used real Android Settings scroll controls and the production AgentService, ApiClient request/parser and controller. Every HTTPS call was intercepted. A fixed **300 ms simulated provider delay** was used for each inference response, including completion. Both modes completed and verified three successful controls per task:

| Mode | Median elapsed | AI requests per task |
|---|---:|---:|
| Fast I: guarded plan | 3,117 ms | 2 |
| Fast II: current-screen choice | 3,905.5 ms | 4 |

Fast I was **20.2% faster in this fixture** and halves requests for this three-action task. [Raw samples and APK hash](../dist/fast-benchmark-0.4.0.json). This is not a live OpenAI latency or model-quality benchmark: different output lengths, planning errors, actual account models, internet conditions and real app behavior can change the result. No live account, Cornell data, Gmail action or game was used. The exact new decision offering named by the user remains unspecified; Fast II above is explicitly an OpenAI structured-choice prototype.

`./scripts/build.sh :app:lintDebug` passed with **0 errors, 13 warnings**. APK version 0.4.0/code 6 is 502,978 bytes and retains the previous v2 signing certificate. `./scripts/inference-smoke.sh` passed **70 checks** including both strict response formats, fractional/conflicting/out-of-range choices, disabled/password targets, changed markers, blocked consequential taps and editable fields with changed text.

`./scripts/auth-smoke.sh` passed **178 native checks, 0 failures**, using 144 intercepted HTTPS requests. Added coverage includes matching task completion in both modes, bounded plans, fresh ID resolution, password/package/ambiguous target rejection, mismatch discarding the queue and actually replanning, Stop cancelling queued actions, and the monochrome checkbox. Earlier auth, transport, recovery, draft, human-input, UI-state and voice checks still pass. [Native results](../dist/auth-smoke.txt). The first benchmark fixture used an unstable Settings header and was corrected to the stable search marker; reported samples are from the passing run.

Development spending remains **$0**. Test account/task data and instrumentation were removed from the project emulator after verification. Runtime still uses direct ChatGPT plan authorization and needs no computer or separate API key.

## Earlier Android release: Tango 0.3.0

This update reduces routine interruptions and replaces the green interface with black backgrounds, white text/actions, and neutral borders. The home screen contains command input, Speak/Run, current task status and controls. The journal is behind **Task details**; setup instructions and **Copy AI details** are in **Account**. Required questions and connection errors appear when relevant. ChatGPT sign-in remains the entry gate.

The policy now asks the model to recover from failed controls, slow interfaces and navigation uncertainty using fresh observations and actual action outcomes. Questions carry a blocker kind and evidence so routine retry questions can receive a recovery consultation. Required facts, security steps, material choices and missing authorization remain human decisions. No passwords, email, Cornell forms or paid inference were used for development testing.

Five unchanged observations or three failed controls now produce recovery context rather than immediately asking the user. A routine question is reconsidered with a fresh observation. Safe Home/return-to-known-app recovery avoids Back and preserves editable forms or drafts; repeated recovery without progress remains bounded. A blocked Send action for a draft is recorded as a recovery condition and cannot dispatch. The run checkpoint is 30 minutes, 500 actions or 650 decisions. Password/OTP requests are converted to a local handoff telling the user to complete the security step directly in the app.

Opening signed-in Tango cancels pending inference and quietly holds control during inspection, settings or editing. Leaving Tango restarts with a fresh decision. Prior replies cannot dispatch or become a completed result after Pause, Stop or an input hold. Inspection time is excluded from the run deadline.

Adaptive accessibility settling replaces the old fixed 900 ms startup, 700 ms action and 250 ms extra wait delays. The controller caches installed-app metadata for five minutes with package-change invalidation, overlaps the first enumeration with screenshot capture, and takes fresh nodes and attempts a new image for every decision. Rapid capture requests wait for a 350 ms interval; [AOSP defines a 333 ms minimum](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java). No old screenshot is reused. Voice auto-run now offers a one-second edit window, cancelled by focusing the transcript and configurable in Account.

`./scripts/build.sh :app:lintDebug` passed: **0 errors, 12 warnings**. The final APK is version 0.3.0/code 5, 460,202 bytes, with a verified v2 signature matching the previous release certificate. `./scripts/inference-smoke.sh` passed **54 checks**, with no live inference.

`./scripts/auth-smoke.sh` passed **167 native checks, 0 failures** against the final APK. [Raw result](../dist/auth-smoke.txt). The suite intercepts all HTTPS requests: 41 auth requests, two catalogs, 36 wire transport requests, four service replies, 31 autonomy replies and one synthetic voice task. It used 13 dummy code exchanges and generated RSA fixtures, with no live sign-in or inference.

Coverage includes eight unchanged wait actions and five failed controls continuing without a human question, routine ask reconsideration, required facts and conservative unknown-question handoffs, bounded recovery, draft Send protection, canonical password/code handoffs, human inspection/editing holds, and fresh replanning after release. Delayed Pause/Stop replies execute zero actions. Signed-out typed, microphone, shortcut and direct-service paths remain gated. Seven UI states were captured and checked for monochrome colors, collapsed history, relevant controls and retained input. [Home preview](../dist/tango-ui-idle.png). [Clean first launch](../dist/tango-signin.png).

The actual speech-result callback was given a synthetic transcript: focusing the field cancelled the pending command, Account could disable auto-run, and task submission reached the native service in **1,049 ms**. This does not measure microphone recognition or spoken-command quality.

Matched local fixture runs on the same emulator, before and after the performance patch:

| Routine | Before | Final | Reduction |
|---|---:|---:|---:|
| Eight waits then completion | 4,288 ms | 4,095 ms | 4.5% |
| Five failed controls then completion | 5,317 ms | 2,987 ms | 43.8% |
| Routine question then recovery | 1,662 ms | 1,466 ms | 11.8% |

These are one-run local harness measurements with an immediate simulated provider, not live task speed predictions. [Comparison and APK hashes](../dist/autonomy-performance.json). Rapid observation captured **four of four** fresh images in the final APK, compared with one of four before throttling. The earlier 3 ms observation median mostly measured failed screenshot calls and is excluded from speed claims; the final interval wait obtains actual pixels. Test account/task data and the external test APK were removed, and test accessibility was disabled after verification. Development spending remains **$0**.

The new release is a prototype. Live phone behavior and model decision quality still require S23 testing. The earlier successful sign-in report and physical-device limits below remain applicable.

## Earlier Android release: Tango 0.2.2

Tango now runs its own observe → decide → act → verify harness on Android and connects directly to OpenAI over HTTPS. The first screen requires **Continue with ChatGPT**; an identity-only sign-in does not unlock commands without ChatGPT plan permission. No desktop bridge, pairing token, API-key mode, or OpenRouter account is part of the current Android flow.

This is a prototype tested in an Android 15 ARM emulator. The user reported successful sign-in and enabled phone control on an S23 after the 0.2.1 update, then encountered an inference response-format error. Their screenshots also show a spoken command in the task journal. Completed live inference, phone actions, the Cornell email/form workflow, and Clash Royale remain unverified on the S23.

### S23 inference response report and transport changes

The old **OpenAI did not return the required inference stream** error occurs only after a 2xx HTTP response whose `Content-Type` is absent or does not contain `text/event-stream`. Tango rejected that response before inspecting its body. The screenshots establish that this guard was reached; they do not reveal the returned media type, body, selected model, or request ID.

Version 0.2.2 inspects a bounded body prefix and parses SSE regardless of the media header. A JSON fallback accepts only an explicitly completed Responses document with the expected envelope and output; JSON error bodies and admission details are surfaced as errors. Partial, empty, malformed, and HTML responses do not dispatch actions. The request remains `store:false` and `stream:true`. Protocol errors preserve credentials and do not repeat an identical invalid response request automatically.

**Copy AI connection details** is available on the home screen after an error and in Account. It copies transport metadata, selected model, request ID, and app version without tokens, command/screen text, or response payloads. A signed-in label no longer implies that inference has succeeded. The task's recovery prompt no longer attributes every transport failure to the user's account or model.

### S23 sign-in report and recovery changes

The browser's **Authorization received** page establishes that the loopback callback reached Tango. It does not establish a successful token exchange or verified identity. The old error handler grouped all I/O failures under a generic internet message, so the screenshots do not identify the failing request.

Version 0.2.1 waits for Tango to return to the foreground before exchanging a browser authorization code, prefetches public signing keys before opening the browser, preserves incomplete registration metadata, and encrypts returned tokens before later identity verification. An interrupted signing-key lookup can resume verification without repeating the one-use token exchange. Incomplete registrations and unverified replies cannot unlock AI. A new activity can attach to an existing attempt after a configuration change. Copyable diagnostics identify the operation and error category without credentials.

The original S23 failure has **not** been reproduced. Android Data Saver blocking background traffic is a possible explanation, not an observed cause on the user's phone.

[Real Android networking probes](../dist/auth-network-diagnostic.txt) reached OpenAI's public discovery and JWKS endpoints with HTTP 200, and explicitly invalid dummy token requests returned expected JSON HTTP 400 using both fixed-length and buffered request bodies. Controlled local HTTPS probes returned 401, 403, and 302 normally. All nine probes passed; no user credentials or inference requests were used. These establish emulator transport behavior, not successful S23 authorization.

### Build and clean first launch

- `./scripts/build.sh :app:lintDebug`: passed with **0 lint errors, 25 warnings**, primarily localization/legacy compatibility.
- Version 0.2.2, version code 4; native Java application, minimum Android 11/API 30, target/compile API 35.
- The final development APK is 439,174 bytes, has a verified APK v2 signature, installed, and ran without a Tango process crash.
- Test credentials, saved accounts, task data, and test accessibility settings were cleared after verification. [Clean first-launch screenshot](../dist/tango-signin.png).
- The signed-out screen exposes the ChatGPT login button and usage settings. Command input, microphone activation, and phone-control setup remain gated until authenticated AI access is present.

### Native OAuth, login gates, and current agent loop

`./scripts/auth-smoke.sh`: **129 checks passed** in the final APK. [Raw instrumentation result](../dist/auth-smoke.txt).

The test process intercepts every HTTPS connection. It uses generated RSA signatures and fixture OIDC/model responses, an intercepted external-browser launch, the real `127.0.0.1` callback listener, Android Keystore, and the real agent service. It performs no live authorization or inference.

| Check group | Verified behavior |
|---|---|
| First launch | Login appears before commands; legacy bridge/API credentials cannot bypass it |
| Signed-out commands | Typed command, microphone method, Continue, steering, direct service start/resume/steer, and restored task cannot run |
| Voice/shortcut entry | Assistant/voice-assist intents and shared tile shortcut remain at login with no microphone; native double-volume handler also remains gated |
| Signed-out network | Zero provider HTTPS requests |
| Recovery | Blocked restoration preserves saved task account and history; incomplete registrations and encrypted token replies survive auth-manager restart without granting unverified AI access |
| OAuth request | Stable phone host ID, new state/nonce, RFC 7636 S256 PKCE, exact loopback callback, direct AI scope, and issued client ID reuse |
| Callback | Wrong state/host rejected before exchange; replay rejected; an expired code retries using its issued registration; valid background callback waits for foreground return and a replacement activity listener can finish |
| ID token | Valid RSA signature accepted; tampering, issuer/audience/nonce/time errors, unsupported signing algorithm, remote-key injection, duplicate JWKS keys, and invalid multi-audience tokens rejected |
| AI permission | Identity-only grant and scope-prefix spoofing cannot authorize inference; revoked direct scope blocks further AI |
| Session persistence | Encrypted access/refresh/ID token record; rotating refresh tokens and expiry stored together; concurrent refreshes serialized |
| Renewal failures | Transient failures preserve credentials; interrupted verification retains the rotated reply without a second refresh POST; terminal renewal failure clears credentials while retaining the account/client mapping |
| Dispatch | Valid task account allowed; different account and paused task denied |
| Sign out | Local AI access blocked immediately; renewable session revocation attempted; tokens cleared and registration retained |
| Current agent loop | Direct HTTPS/SSE fixture `ask` → waiting; human “Board Game Club” reply preserved as trusted instruction; resume → done |
| Late responses | Delayed Pause/Stop responses discarded with zero actions and Settings still foreground |

The native fixture intercepted 83 HTTPS requests: 41 OAuth/auth requests, two model catalogs, 36 inference transport checks, and four AgentService replies. It performed 13 dummy authorization-code exchanges. All calls used offline fixtures. The delayed provider response deliberately survives transport disconnection to verify cancellation at the service and dispatch boundaries.

Public read-only discovery at [OpenAI's OIDC configuration](https://auth.openai.com/.well-known/openid-configuration) also confirmed that the production issuer, authorization/token/revocation/JWKS endpoints, S256 PKCE, and RS256 signing match the implementation. No real account token was requested.

### Current direct-inference transport

`./scripts/inference-smoke.sh`: **54 checks passed** against production `ApiClient` request/catalog/SSE-parser helpers with JVM Android/auth stubs. [Raw result](../dist/inference-smoke.txt). This complements the actual-APK integration above; it does not establish live account eligibility.

- Requires a validated completed SSE response or an explicitly completed Responses JSON document before accepting an action; truncated, failed, incomplete, malformed, and mid-stream error responses cannot dispatch.
- Handles cancellation and quota errors, including errors that arrive after stream start.
- Uses the account-advertised model catalog, preserves server ordering, and honors explicit screen/input restrictions.
- Sends the preview-compatible request shape: `store: false`, `stream: true`, full task/history input and instructions; no server-side response continuation or unsupported generation fields.
- Builds bounded screen/history payloads, rejects unsupported model tool calls, and limits response size.
- Handles auth, workspace/policy, usage-limit, permission, rate-limit, and transient server errors without turning every provider error into sign-out.

### Actual-APK inference transport checks

The native suite adds **41 wire checks across 36 full `ApiClient.decide` requests**, using the actual Android application and HTTP connection seam. It accepts completed SSE with normal, missing, JSON, and plain-text media headers, and completed Responses JSON with normal, missing, and SSE headers. It rejects empty/HTML, bare actions, unfinished/queued responses, truncated JSON/SSE, event wrappers, trailing garbage, refusals, unsupported tool output, oversized payloads, and object-coerced text/delta fields.

JSON error/detail bodies returned with HTTP 200 preserve provider diagnostics and credentials; protocol failures pause without identical automatic retries. An exact `token_revoked` error inside HTTP-200 SSE still invalidates only the matching account. Cancellation and account changes during reading block both SSE and JSON results. Persisted diagnostics contain transport metadata only, and a successful account-validated result clears them.

The real AgentService fixture asks through SSE, resumes to done through completed JSON, and discards a delayed JSON reply after Stop with zero phone actions. This remains fixture verification; the S23 response body that triggered the original error is still unknown.

### Direct-sign-in limits

OpenAI's [official OSS registration flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) define this integration. Account/workspace/region eligibility and the user granting ChatGPT plan usage still determine whether actual sign-in and inference succeed. The APK does not promise access to an ineligible account.

The preview supports model text/image inference but does not provide hosted native computer use or audio transcription through this authorization flow. Tango executes Android actions locally and uses Android speech recognition separately. An OpenRouter account is unnecessary for this route; its optional future value would be model/provider routing and separately billed inference, rather than ChatGPT plan authorization.

### New sign-in recovery coverage

Fixtures inject timeout, EOF, and TLS failures into the token exchange and verify exactly one POST, retained registration metadata, and phase-specific error messages without secrets. A signing-key failure after tokens arrive preserves an encrypted unverified reply. Constructing a fresh auth manager, then retrying verification, completes sign-in with no browser launch and no second code exchange. The same restart recovery is checked after rotating a refresh token. Permanently invalid refreshed identity/schema data clears only the matching session without replaying a rotating token. An expired staged refresh survives auth-manager restart and fails closed with zero POSTs. Copyable diagnostics are checked for operation, transport stage, category, and absence of credential markers.

## Earlier version 0.1 verification

The following results were obtained before the phone-only redesign. They are retained as historical evidence, **not** as a live-login claim for 0.2.2.

### Native phone tools

[Earlier instrumentation result](../dist/accessibility-smoke.txt): actual Settings observation (27 nodes), native JPEG screenshot (576 × 1280), tap, text replacement, Back, stale-node rejection, password redaction/screenshot withholding, mandatory task generation, pause denial, duplicate/coordinate Send protection for draft requests, explicit later human send instructions, and invalid coordinate rejection passed.

These underlying tools remain in 0.2.2, with the additional current account/plan dispatch gate tested above. The earlier standalone instrumentation script assumes the old service setup and is not a current authenticated integration test.

### Retired computer companion

The old deterministic bridge loop passed ask/resume and late Pause/Stop cancellation tests. The old bridge's 27 tests passed. One synthetic Gmail-draft inference using the installed native Codex/ChatGPT account returned a valid Navigate-up action in 8,531 ms with zero host-tool items. No phone action or external email was sent. This consumed existing Codex quota and did not test the new direct OAuth flow.

The legacy `bridge/` folder remains archived source and is not used by the Android app. Its old tests and companion-launch setup do not verify or configure 0.2.2.

## Physical-device limits

Samsung's built-in double-press **Open app** setting supplies the intended S23 Side-button trigger. Actual S23 Side-button mapping, One UI lifecycle, microphone transcription, secure mail pages, and Cornell registration requirements still need device testing.

Earlier emulator assistant metadata/session and Quick Settings launch checks passed. Synthetic ADB/emulator-console volume events did not launch Tango; the current native volume-handler test checks its login gate, **not physical event delivery**. The earlier emulator speech provider retried a missing on-device language pack but ended with `ERROR_SERVER_DISCONNECTED` without real audio transcription. Spoken-command quality has not been verified on the S23.

The phone stays unlocked and visible while working; passwords and MFA need the user. Model/screenshot latency means competitive real-time Clash Royale play is best effort and a completed/won game is not guaranteed.

**Out-of-pocket development spending: $0.** Current tests used no paid API requests, hosting purchases, or model quota. The earlier version's one live smoke used existing Codex quota.
