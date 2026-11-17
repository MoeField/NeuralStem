#!/bin/bash
# 把 APK 写入本地 repo，生成/更新签名 index。
#
# 用法：
#   FDROID_BIN=/path/to/fdroid ./fdroid/scripts/update-local-repo.sh \
#     app/build/outputs/apk/release/app-arm64-v8a-release.apk
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOCAL="$ROOT/fdroid/local"
FDROID_BIN="${FDROID_BIN:-fdroid}"
APK="${1:-$ROOT/app/build/outputs/apk/release/app-arm64-v8a-release.apk}"

if [ ! -f "$LOCAL/config.yml" ]; then
  echo "先运行 fdroid/scripts/init-local-repo.sh" >&2
  exit 1
fi
if [ ! -f "$APK" ]; then
  echo "APK 不存在：$APK" >&2
  exit 1
fi

mkdir -p "$LOCAL/repo" "$LOCAL/metadata"
cp "$ROOT/fdroid/metadata/pub.miku.neuralstem.yml" "$LOCAL/metadata/"
# 自建源只保留最新 APK，避免同名版本被 fdroid 判定为重复。
find "$LOCAL/repo" -maxdepth 1 -name 'pub.miku.neuralstem_*.apk' -delete
cp -f "$APK" "$LOCAL/repo/"
cd "$LOCAL"

# --create-metadata 只用于首次生成缺失条目；已有 metadata 会保留。
"$FDROID_BIN" update --create-metadata --rename-apks
echo
echo "repo ready: $LOCAL/repo"
echo "发布时把 $LOCAL/repo 放到 gh-pages 的 fdroid/repo/ 下。"
