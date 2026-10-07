#!/usr/bin/env bash
# Project-local Android SDK and Java. Downloads only official, free distributions.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_TOOLS="$TASK_ROOT/tools"
TASK_DOWNLOADS="$TASK_TOOLS/downloads"
TASK_SDK="$TASK_TOOLS/android-sdk"
mkdir -p "$TASK_DOWNLOADS" "$TASK_SDK/cmdline-tools"

case "$(uname -s)" in
  Darwin) TASK_OS=mac; TASK_SDK_OS=mac ;;
  Linux) TASK_OS=linux; TASK_SDK_OS=linux ;;
  *) echo "Use Android Studio with JDK 21 on this operating system." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) TASK_ARCH=aarch64 ;;
  x86_64|amd64) TASK_ARCH=x64 ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

download() {
  local task_url="$1" task_file="$2"
  if [[ ! -f "$task_file" ]]; then
    curl --fail --location --retry 3 "$task_url" -o "$task_file.part"
    mv "$task_file.part" "$task_file"
  fi
}
verify_sha256() {
  local task_file="$1" task_expected="$2" task_actual
  if command -v shasum >/dev/null; then
    task_actual="$(shasum -a 256 "$task_file" | cut -d ' ' -f 1)"
  else
    task_actual="$(sha256sum "$task_file" | cut -d ' ' -f 1)"
  fi
  if [[ "$task_actual" != "$task_expected" ]]; then
    echo "Checksum mismatch: $task_file. Remove it and retry." >&2
    exit 1
  fi
}

# Temurin metadata includes a checksum. Save it locally for repeatable subsequent builds.
if [[ ! -f "$TASK_TOOLS/java-home.txt" ]] || [[ ! -x "$(cat "$TASK_TOOLS/java-home.txt" 2>/dev/null)/bin/java" ]]; then
  if [[ ! -f "$TASK_DOWNLOADS/jdk-metadata.json" ]]; then
    curl --fail --silent --show-error --location \
      "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$TASK_ARCH&image_type=jdk&os=$TASK_OS&vendor=eclipse" \
      -o "$TASK_DOWNLOADS/jdk-metadata.json"
  fi
  TASK_JDK_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["binary"]["package"]["link"])' "$TASK_DOWNLOADS/jdk-metadata.json")"
  TASK_JDK_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["binary"]["package"]["checksum"])' "$TASK_DOWNLOADS/jdk-metadata.json")"
  download "$TASK_JDK_URL" "$TASK_DOWNLOADS/jdk21.tar.gz"
  verify_sha256 "$TASK_DOWNLOADS/jdk21.tar.gz" "$TASK_JDK_SHA"
  mkdir -p "$TASK_TOOLS/jdk21"
  tar -xzf "$TASK_DOWNLOADS/jdk21.tar.gz" -C "$TASK_TOOLS/jdk21" --strip-components=1
  if [[ "$TASK_OS" == mac ]]; then
    printf '%s\n' "$TASK_TOOLS/jdk21/Contents/Home" > "$TASK_TOOLS/java-home.txt"
  else
    printf '%s\n' "$TASK_TOOLS/jdk21" > "$TASK_TOOLS/java-home.txt"
  fi
fi
export JAVA_HOME="$(cat "$TASK_TOOLS/java-home.txt")"
export ANDROID_HOME="$TASK_SDK"
export ANDROID_SDK_ROOT="$TASK_SDK"
export ANDROID_USER_HOME="$TASK_TOOLS/android-user"
export ANDROID_AVD_HOME="$TASK_TOOLS/avd"
export PATH="$JAVA_HOME/bin:$TASK_SDK/platform-tools:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

if [[ ! -x "$TASK_SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  if [[ "$TASK_SDK_OS" == linux ]]; then
    TASK_COMMANDLINE_NAME=commandlinetools-linux-15859902_latest.zip
    TASK_COMMANDLINE_SHA=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
  elif [[ "$TASK_ARCH" == aarch64 ]]; then
    TASK_COMMANDLINE_NAME=commandlinetools-mac_arm64-15859902_latest.zip
    TASK_COMMANDLINE_SHA=835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e
  else
    TASK_COMMANDLINE_NAME=commandlinetools-mac_x86_64-15859902_latest.zip
    TASK_COMMANDLINE_SHA=c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3
  fi
  download "https://dl.google.com/android/repository/$TASK_COMMANDLINE_NAME" "$TASK_DOWNLOADS/$TASK_COMMANDLINE_NAME"
  verify_sha256 "$TASK_DOWNLOADS/$TASK_COMMANDLINE_NAME" "$TASK_COMMANDLINE_SHA"
  TASK_UNPACK="$TASK_TOOLS/commandline-unpack"
  mkdir -p "$TASK_UNPACK"
  unzip -q "$TASK_DOWNLOADS/$TASK_COMMANDLINE_NAME" -d "$TASK_UNPACK"
  mv "$TASK_UNPACK/cmdline-tools" "$TASK_SDK/cmdline-tools/latest"
  rmdir "$TASK_UNPACK"
fi

# The project setup authorizes the SDK terms for local development. Disable pipefail
# around yes because it exits with SIGPIPE when sdkmanager finishes reading.
set +o pipefail
yes | "$TASK_SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TASK_SDK" --licenses >/dev/null
TASK_LICENSE_RESULT=${PIPESTATUS[1]}
set -o pipefail
if [[ "$TASK_LICENSE_RESULT" -ne 0 ]]; then exit "$TASK_LICENSE_RESULT"; fi
"$TASK_SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TASK_SDK" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
printf 'sdk.dir=%s\n' "$TASK_SDK" > "$TASK_ROOT/local.properties"
"$JAVA_HOME/bin/java" -version
echo "Android SDK ready: $TASK_SDK"
