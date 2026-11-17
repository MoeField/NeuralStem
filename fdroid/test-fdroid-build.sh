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

# 默认从 GitHub 克隆 spike 分支；本地 git 源在 fdroidserver 2.4.2 下
# 会拿不到 commit（build dir 不生成），所以测试走真实远程源。
REPO_URL="${FDROID_REPO_URL:-https://github.com/MoeField/NeuralStem}"
COMMIT="${FDROID_COMMIT:?set FDROID_COMMIT to the pushed commit hash}"

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
Repo: $REPO_URL
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

# 测试环境补丁：Debian trixie 的 fdroidserver 2.4.2 的 gradlew-fdroid
# 只识别到 Gradle 8.14.2，不认识项目用的 9.8.2。官方 buildserver 需要
# 更新 fdroidserver；这里只在测试容器内补 hash，让后续步骤继续暴露问题。
GRADLEW_FDROID=/usr/lib/python3/dist-packages/gradlew-fdroid
if ! grep -q "'9.8.2'" "$GRADLEW_FDROID"; then
  sed -i "/'8.14.2'/a\        '9.8.2') echo '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607' ;;" "$GRADLEW_FDROID"
fi

cd "$WORK"
# fdroidserver 2.4.2 在 set_FDroidPopen_env() 里先读 build/<app> 的 git
# SOURCE_DATE_EPOCH，再执行 prepare_source；首次构建如果目录不存在会直接
# TypeError。官方更新的 fdroidserver 已修；这里先手动 clone 兜底。
mkdir -p build
if [ ! -d "build/$APP_ID/.git" ]; then
  git clone -q "$REPO_URL" "build/$APP_ID"
fi
git -C "build/$APP_ID" fetch -q origin
git -C "build/$APP_ID" checkout -q "$COMMIT"

# --test：不写入正式 repo，不要求签名；-f 允许 test 模式下继续。
fdroid build --verbose --test -f "$APP_ID:$VER_CODE" 2>&1 | tee /tmp/fdroid-build.log
