#!/bin/bash
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
MARK="$SDK/.pinned-sdk-ready"
if [ -f "$MARK" ]; then
  echo "Android SDK already prepared: $(cat "$MARK")"
  exit 0
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

mkdir -p "$SDK"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# API 37 当前是 preview channel；与 CI 保持一致。
if ! sdkmanager --channel=3 --install "platforms;android-37" >/tmp/sdk-platform.log 2>&1; then
  sdkmanager --channel=3 --install "platforms;android-37.0" >/tmp/sdk-platform.log 2>&1
fi
sdkmanager --channel=3 --install "build-tools;37.0.0" >/tmp/sdk-buildtools.log 2>&1 \
  || sdkmanager --channel=3 --install "build-tools;36.0.0" >/tmp/sdk-buildtools.log 2>&1
sdkmanager --install "ndk;30.0.16248370" >/tmp/sdk-ndk.log 2>&1
sdkmanager --install "cmake;4.1.2" >/tmp/sdk-cmake.log 2>&1

{
  echo "prepared=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "platforms=$(sdkmanager --list_installed 2>/dev/null | grep -E 'platforms;android-3[0-9]' | tr '\n' ' ')"
  echo "ndk=$(sdkmanager --list_installed 2>/dev/null | grep -E 'ndk;' | tr '\n' ' ')"
  echo "cmake=$(sdkmanager --list_installed 2>/dev/null | grep -E 'cmake;' | tr '\n' ' ')"
} | tee "$MARK"
