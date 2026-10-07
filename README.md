# Tango — Elastic × Mistral Hack Night

This repository is the initial port of Tango for the [Elastic × Mistral NYC Hack Night](https://github.com/AvenueJ/elastic-mistral-hacknight). The existing Android app, local bridge, scripts, documentation, and distributable artifacts are preserved. **Mistral and Elasticsearch integration is planned and has not been implemented.** The app currently uses its original ChatGPT sign-in and OpenAI transport, as documented below.

See [the port notes](docs/initial-port.md) for scope and verification.

A phone-native Android agent for the Galaxy S23. Sign in with ChatGPT, speak a task, and Tango reads the screen, chooses an action, taps/types/swipes, and observes the result. Its own harness runs on Android and calls OpenAI directly over HTTPS. **No computer, Codex process, pairing token, shared Wi-Fi, API key, or OpenRouter account is needed for everyday use.** The phone needs internet.

**APK:** [`dist/tango-debug.apk`](dist/tango-debug.apk), signed development build version 0.6.1 (build 11), Android 11+. The source is provided under the MIT license. This is a prototype, not a Play Store release.

## Set up your S23

1. Copy `dist/tango-debug.apk` to your phone and open it to install or update Tango. For Wi-Fi transfer, run `node scripts/share-apk.mjs --lan` or double-click **Start Tango.command**, then open the printed APK address on your phone. This computer connection is only for downloading the APK; stop the sharing server afterward. USB debugging users can run `./scripts/install.sh`.
2. Open Tango. **Continue with ChatGPT** is the first screen. Finish OpenAI sign-in and consent in your phone's browser, then return to Tango. Approve ChatGPT plan usage as well as identity. Commands, microphone recording, and background actions stay blocked without verified sign-in and AI permission. No API-key or bridge bypass is offered.
3. After sign-in, tap **Enable phone control**, read the disclosure, and enable **Tango phone control** in Accessibility settings. Allow microphone and notifications when prompted. For restricted sideloaded apps, open Tango's Android App info menu and allow restricted settings first. [Android instructions](https://support.google.com/android/answer/12623953).
4. In Samsung **Settings → Advanced features → Side button (or Side key) → Double press → Open app**, select **Tango**. Double press, unlock if necessary, and speak your whole task. The transcript has a one-second edit window before execution. Tap the transcript to cancel the countdown and edit it, or turn off **account icon → Voice & shortcuts → Run voice commands**. [Samsung instructions](https://www.samsung.com/sg/support/mobile-devices/customise-the-side-key-of-your-galaxy-phone/).
5. Use the floating **Tango / Pause / Stop** control or notification while it works. Tap Tango to read a question, answer, or add an instruction. **Continue** observes the screen again. Open **Task details** for the journal and **account icon → Voice & shortcuts → Phone setup** for shortcut help. Complete passwords, MFA and CAPTCHAs directly in the original app.

If the browser says **Authorization received**, return to Tango to finish. Version 0.2.1 defers the token exchange until Tango is in the foreground. If verification is interrupted, **Continue with ChatGPT** resumes a saved encrypted reply or reuses the incomplete registration for a fresh authorization. An incomplete registration does not unlock commands. Network errors identify the failed sign-in phase; **Copy sign-in details** copies a diagnostic report without account credentials.

Alternative triggers: **Talk to Tango** Quick Settings tile, Android default-assistant gesture, or double volume-down while the screen is on. These open the same sign-in gate when disconnected. The power button uses Samsung's built-in mapping; Tango cannot bypass the lock screen.

## Chats and model modes

The main screen follows the ChatGPT mobile layout: a black conversation canvas, circular menu and account controls, and a rounded composer anchored above the keyboard. There is one chat experience. Open the menu or swipe right across the conversation for the thread sidebar, then tap **Chat** to start a new conversation. Search chats with the search icon. Each chat has a visible **⋯** options button for renaming, archiving, or deleting it; long-press opens the same actions. **Archived** opens archived conversations.

Chats keep their messages, task details, reasoning history, and unsent command/reply drafts on the phone. Follow-up messages continue the selected conversation, including after a task finishes. Switching conversations pauses phone control; restored tasks require **Continue**. Each ChatGPT account has separate chats, and the existing single-task history is migrated once.

Tap the effort dial in the composer to select **Instant**, **Medium**, or **High**. The floating in-app picker has a blue capsule, three stops, and a draggable white thumb. It saves each selection immediately; tap outside or use the × close control to dismiss it. The picker persists your choice and uses an available account model's advertised reasoning controls. Instant selects the lowest supported reasoning effort; Medium and High request their corresponding advertised effort. Models without reasoning metadata keep their provider default. Account opens with model mode, usage, and connection controls; expand **Advanced** for the model catalog, fast mode, and diagnostics, or **Voice & shortcuts** for voice preferences and phone setup.

Tap **+** for **Camera**, **Photos**, or **Files**. Camera captures a full-resolution photo; Photos and Files support multiple selections. Attachment previews stay with the chat draft until you send them, and each can be removed with its × button. Images, PDFs, and text files are included in the model request. Supported formats are JPEG, PNG, WebP, PDF, UTF-8 TXT/Markdown/CSV/JSON: up to 4 files per message, 10 MB per file (1 MB for text), and 20 MB per chat. A selection is rejected if its originating account or chat changes while the picker is open.

One microphone tap immediately changes the control to the stop square; one stop tap enters transcription and disables repeat taps until the result arrives.

## Interaction and interruptions

The sidebar follows your finger, and screen and sheet transitions ease into place. Buttons depress on touch, then return on release or cancellation; new messages arrive with a brief fade. Haptics mark meaningful actions and effort changes. Transitions respect Android's animation setting, and haptics respect the phone's feedback setting.

Task details, chat actions, account choices, and confirmations use rounded sheets inside Tango. Close a sheet with its visible × button, Back, the dimmed background, or a downward drag on its handle. Destructive confirmations include **Cancel**. The sidebar also has a visible close control, and temporary feedback messages can be dismissed. Android still owns permission prompts, the camera, and file selection.

Drafts save while you type and when you leave Tango. Returning after an interruption keeps the selected chat and draft; screen recreation also restores your scroll position, Account disclosures, thread search, and an unfinished chat rename. Reopening through the launcher keeps an unfinished draft or sheet in place instead of starting voice input over it. Recording stops when Tango leaves the foreground, and an interrupted voice countdown leaves an editable draft instead of sending it later. File imports can finish across screen recreation; progress and completion return to the originating chat. Sheets settle when interrupted, and a pending sheet action is not executed in the background.

## Connect to Codex

Choose **Connect to Codex** in the sidebar or Account and tap **Connect**, then connect the phone over USB with debugging enabled. Use **Copy setup** for the connection command, or open **Connection details** to view the revocable token and ADB forwarding command. Its optional foreground connection listens only on the phone's loopback interface. Your everyday tasks still run directly from the phone.

The included [Codex connection guide](docs/connect-codex.md) explains how to use `scripts/codex-tango.mjs` as a command-line client or a stdio MCP server. Codex can send prompts, follow up in a thread, read results, inspect the current phone screen, and pause or stop tasks. Sending a prompt hands the foreground back to the target apps as tapping Send does. Disconnect, sign-out, account changes, or process restart revoke the token. ChatGPT credentials stay on the phone.

## Direct ChatGPT access

This version implements OpenAI's documented **Sign in with ChatGPT for open-source and locally hosted apps**, including the optional permission to use a ChatGPT plan for eligible Responses API requests. It is currently a preview. Account eligibility, workspace policy, model availability and usage limits are controlled by OpenAI. An OpenAI identity alone does not enable AI: Tango checks the returned plan permission and refuses inference without it.

The phone opens the real OpenAI authorization page in the system browser, using a loopback callback, PKCE, state and nonce. Initial registration uses `dynamic_agent_client`; subsequent sign-ins reuse the issued client ID for the verified account. ID tokens are checked against OpenAI's signing keys, issuer, audience, expiry and nonce. A stable opaque host ID identifies the phone installation. Tokens are encrypted with Android Keystore, app backup/device transfer is excluded, refreshes are serialized, and sign-out clears local credentials and attempts remote revocation.

Account settings offer saved-account selection, model selection from the account's advertised catalog, **Usage**, and sign-out. Sign-in identifies ChatGPT plan usage. A task remains associated with the account that started it. Switching account or signing out stops it; returning to another account cannot silently resume its tasks.

Inference goes directly to `https://api.openai.com/v1/responses`. It uses `store:false`, `stream:true`, local context, screenshots and validated action output. It never uses first-party Codex credentials, borrowed client IDs or the retired companion transport. The APK has no configurable external inference endpoint. OpenAI preview errors and lost permissions pause the task; Tango does not silently spend API credits or change providers.

The transport added in version 0.2.2 inspects the bounded response body when its media header is missing or incorrect, and accepts actions only from a completed Responses event or an explicitly completed Responses JSON document. JSON error responses are surfaced directly. Empty, HTML, partial, and invalid responses cannot operate the phone. After an AI connection error, use **account icon → Advanced → Copy AI details**. This report includes transport metadata and the selected model; it excludes tokens, commands, screen content, and response payloads.

Official references: [overview](https://developers.openai.com/siwc/token-sharing-open-source), [registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [account/session management](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

OpenRouter is unnecessary for this route. It would add multi-provider model routing and fallback, but uses its own authorization and billing. It does not substitute for ChatGPT plan permission. [OpenRouter OAuth](https://openrouter.ai/docs/guides/overview/auth/oauth).

## Your Cornell task

Speak or type the full workflow: use `dt579@cornell.edu`, research the current Networks and Markets TA, verify the address and advisor eligibility, write a concise invitation and save it as a draft, find the current official club-registration form, fill known details, and register when requirements are satisfied. The spoken email is an editable interpretation of “DT five seven nine.”

Tango researches current staff and forms at runtime. It asks for required missing facts, such as officers, an eligible advisor or an ambiguous course. It must not invent answers or claim submission without observing confirmation. An explicit registration request authorizes submission; a draft request blocks sending the email. Your actual Cornell account has not been used for development verification.

The command controls use a microphone icon and a send icon. While listening, the microphone becomes a stop icon. Both controls retain screen-reader labels and long-press hints.

## Optional fast mode

From **account icon → Advanced**, turn on **Fast mode I** to let ChatGPT plan a current action plus up to three routine follow-up steps. Tango resolves controls against a fresh accessibility tree before each step, requires a matching app and screen marker, and replans on ambiguity, mismatch, failure or expiry. It never caches screenshots or snapshot IDs. Sending, submitting, saving, security and other consequential taps require a fresh AI decision. Completion is verified through another AI observation. Pause, Stop, account changes and opening Tango cancel queued steps. The switch is off initially.

Fast II is a development comparison using OpenAI Structured Outputs to choose an index from current-screen controls, with ordinary actions for typing and other variable parameters. It is not a separate OpenAI decision model, and it still makes one AI request per step. Only the selected approach has a settings switch. Native benchmark results distinguish simulated provider latency from live inference; live ChatGPT latency and plan quality require an eligible signed-in phone.

## Agent behavior and privacy

- Serial observe → decide → act → verify loop, with current snapshot targets and screenshot coordinates for canvas apps.
- Clarification is a last resort. Tango uses contact names, affectionate emojis, conversation details and other observed evidence to choose a clear match. Non-security model questions receive an internal reconsideration before reaching you.
- Questions stay paired with your replies across restarts. Continue accepts a single proposed candidate within the original task; it does not invent an absent required fact. Earlier saved questions migrate when you continue them.
- Launch installed apps, browse, tap, replace text fields, scroll, swipe, Back/Home.
- Reobserve and replan after failed controls or an unchanged screen. Routine retry questions receive an autonomous recovery consultation; safe return-to-app recovery preserves forms and drafts. Required facts, security steps and material choices still need the user. Repeated recovery without progress is bounded.
- Each run allows up to 30 minutes, 500 phone actions or 650 decisions before a checkpoint. Continue grants another run. Transient connection failures use bounded backoff; permanent provider errors need resolution.
- Adaptive settling replaces fixed waits after actions. Fresh nodes and screenshots are taken for each decision; the installed-app inventory is cached and invalidated when packages change.
- Accept steering while running. Pause/Stop invalidates model replies and is checked again at Android dispatch.
- Opening Tango quietly holds control while you inspect or edit. Returning to another app resumes from a fresh observation; delayed actions from before the hold cannot replay.
- Interrupted tasks recover paused. Authentication is checked before start/resume, observation and actual action dispatch.
- Screen/email/web text is evidence, never authority to change the user's instructions.

Visible screen text and screenshots are transmitted to OpenAI during tasks. The private local journal stores commands, actions and results, without screenshots or raw model payloads. **Account → Advanced → Clear task history** removes it. Password fields are redacted; password screens pause capture/control. Secure Android windows may be inaccessible.

These safeguards do not guarantee perfect model decisions. The phone stays visible and controllable. Cloud inference is too slow to guarantee competitive real-time Clash Royale play. Payments and account/security changes are handed back to you. Physical S23 voice, power shortcut, Cornell workflow and game behavior still need device testing. **Additional development spending: $0.** ChatGPT plan and credit limits remain authoritative; manage them in ChatGPT settings.

## Build and verify

```sh
./scripts/build.sh
./scripts/build.sh :app:lintDebug
./scripts/inference-smoke.sh
# Requires the local test emulator running as emulator-5580:
./scripts/auth-smoke.sh
```

Build tools are bootstrapped locally under `tools/` on macOS/Linux. Windows users can import the Gradle project in Android Studio with JDK 21. Gradle 8.9, Android Gradle Plugin 8.7.3, compile/target SDK 35; native Java, no Android package dependencies. `dist/tango-debug.apk` is the installable output.

Auth verification uses an isolated emulator and generated protocol fixtures; it does not sign into your account or consume inference quota. Read [docs/verification.md](docs/verification.md) for results and limitations. Historical Codex bridge sources/tests remain in `bridge/` for reference and are not used by the current APK.

Entry points: `MainActivity.java` (account-first UI and voice), `ChatGptAuth.java` (OpenAI account sessions), `ApiClient.java` (direct model transport), `AgentService.java` (native harness), `PhoneController.java` and `PhoneAccessibilityService.java` (Android tools).
