#!/bin/bash
# 在 fdroid/Dockerfile 的 Debian 容器里跑一次本地 fdroidserver 配方构建。
# 用法（容器内）：
#   /workspace/fdroid/test-fdroid-build.sh
#
# 目的不是发布，而是验证：
#   - 源码仓库能在干净 checkout 里构建
#   - metadata 的 Gradle/NDK/output 路径正确
#   - fdroidserver 2.4.2 能否接受当前 AGP 9.5 / Gradle 9.8 / SDK 37 组合
set -euo pipefail

SRC=/workspace
WORK=/tmp/fdroid-test
APP_ID=pub.miku.neuralstem
VER_CODE=41

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
/usr/local/bin/setup-sdk

rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"
fdroid init >/tmp/fdroid-init.log 2>&1 || { cat /tmp/fdroid-init.log; exit 1; }
mkdir -p metadata

# 本测试使用本地 git 源；真实提交时换成 GitHub 上的 tag/commit。
cd "$SRC"
if [ ! -d .git ]; then
  git init -q
  git config user.email "spike@local"
  git config user.name "F-Droid spike"
fi
git add -A
git commit -q -m "fdroid spike build" || true
COMMIT=$(git rev-parse HEAD)

cat > "$WORK/metadata/$APP_ID.yml" <<YAML
Categories: Multimedia
License: MIT
AuthorName: MoeField
WebSite: https://github.com/MoeField/NeuralStem
SourceCode: https://github.com/MoeField/NeuralStem
IssueTracker: https://github.com/MoeField/NeuralStem/issues
Summary: Offline stem separation
Description: |
  Offline audio stem separation and multitrack playback.
RepoType: git
Repo: file://$SRC
Binaries: https://github.com/MoeField/NeuralStem/releases
Builds:
  - versionName: 0.2.21
    versionCode: $VER_CODE
    commit: $COMMIT
    subdir: .
    gradle:
      - yes
    ndk: 30.0.16248370
    output: app/build/outputs/apk/release/app-arm64-v8a-release.apk
YAML

cd "$WORK"
# --test：不写入正式 repo，不要求签名；-f 允许 test 模式下继续。
fdroid build --verbose --test -f "$APP_ID:$VER_CODE" 2>&1 | tee /tmp/fdroid-build.log
