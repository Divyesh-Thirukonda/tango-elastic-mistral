# Initial project port

This project starts from the complete portable Tango workspace supplied on October 7, 2026. Its fresh Git history imports the project in 15 commits, beginning with `init`.

The target event is the [Elastic × Mistral NYC Hack Night](https://github.com/AvenueJ/elastic-mistral-hacknight). Mistral and Elasticsearch will be integrated in a later change. This initial port adds no provider clients, search indices, credentials, or new runtime behavior. ChatGPT sign-in and OpenAI inference remain the current implementation.

## Included

- Android app source, resources, manifest, and Gradle wrapper.
- Local bridge, Codex connection tools, and existing tests.
- Build, installation, packaging, and verification scripts.
- Documentation, branding assets, font licenses, packaged APK, and existing verification artifacts.
- Original MIT license and third-party notices.

## Local files

Downloaded SDKs, the JDK, emulators, Gradle caches, build directories, account state, signing keystores, and machine-specific `local.properties` are excluded from version control. Run `./scripts/build.sh` to bootstrap the local tools and build the app. The imported APK and historical verification artifacts describe the original Tango baseline.

## Verification

Verified in the new workspace on October 7, 2026:

- `./gradlew --no-daemon :app:assembleDebug :app:lintDebug`: passed, using the existing local JDK 21 and Android SDK 35. Compiler deprecation and SDK metadata warnings remain from the baseline.
- `node --test bridge/test/*.test.mjs scripts/test/codex-tango.test.mjs`: 34 tests passed.
- `./scripts/inference-smoke.sh`: 70 checks passed, with zero live inference requests.
- `./scripts/model-mode-smoke.sh`: 37 checks passed, with zero live inference requests.
- `./scripts/codex-connection-test.sh`: 16 offline HTTP parser checks passed.
- Portable file comparison: all Android source, resources, bridge, and scripts match the supplied workspace byte for byte. README and ignore rules were updated, these notes were added, and the source ZIP was regenerated to include them.
- Credential-pattern scan covered portable files and ZIP contents; no matches were found.

The distribution APK and historical smoke reports are preserved from the supplied workspace. Device/emulator workflows were not rerun during this port. Existing test results do not verify any future Mistral or Elasticsearch integration.
