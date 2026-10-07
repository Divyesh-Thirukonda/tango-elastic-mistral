#!/usr/bin/env python3
"""Package distributable Tango source; never include tool caches or account state."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent.parent
output = root / 'dist' / 'tango-source.zip'
roots = ['app/src', 'bridge', 'docs', 'gradle', 'scripts']
files = [root / name for name in ['README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.txt', '.gitignore', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle', 'Start Tango.command']]
for name in roots:
    for candidate in (root / name).rglob('*'):
        relative = candidate.relative_to(root)
        if candidate.is_file() and not candidate.is_symlink() and not any(part in {'.state', '__pycache__', 'node_modules', 'build', '.DS_Store'} for part in relative.parts):
            files.append(candidate)
for name in ['tango-debug.apk', 'SHA256SUMS.txt', 'tango-signin.png', 'tango-ui-signedout.png', 'tango-ui-idle.png', 'tango-ui-running.png', 'tango-ui-waiting.png', 'tango-ui-paused.png', 'tango-ui-done.png', 'tango-ui-account.png', 'tango-ui-account-advanced.png', 'tango-ui-task-details.png', 'tango-ui-sidebar.png', 'tango-ui-model.png', 'tango-ui-keyboard.png', 'tango-ui-attachments-menu.png', 'auth-smoke-0.6.1.txt', 'clarification-smoke-0.6.1.txt', 'model-mode-smoke.txt', 'auth-smoke-0.6.0.txt', 'ui-smoke-0.6.0.txt', 'ui-interaction-smoke-0.6.0.txt', 'ui-reduced-motion-0.6.0.txt', 'auth-smoke-0.5.1.txt', 'ui-smoke-0.5.1.txt', 'voice-controls-smoke.txt', 'codex-connection-smoke.txt', 'auth-smoke-0.5.0.txt', 'ui-smoke-0.5.0.txt', 'auth-smoke.txt', 'auth-smoke-baseline.txt', 'autonomy-baseline.json', 'autonomy-performance.json', 'fast-benchmark.json', 'fast-benchmark-0.4.0.json', 'observe-smoke-baseline.txt', 'observe-smoke-final.txt', 'auth-network-diagnostic.txt', 'inference-smoke.txt', 'accessibility-smoke.txt', 'agent-loop-smoke.txt']:
    files.append(root / 'dist' / name)
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for candidate in sorted(set(files)):
        if candidate.is_file():
            archive.write(candidate, candidate.relative_to(root).as_posix())
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert 'app/build.gradle' in names and 'app/src/main/java/com/tango/phone/ChatGptAuth.java' in names
    assert not any('/.state/' in name or name.endswith(('.keystore', '.jks')) or name == 'local.properties' for name in names)
print(f'{output} ({output.stat().st_size:,} bytes, {len(names)} files)')
