#!/bin/bash
# 初始化本地 F-Droid 二进制源（repo 签名密钥只保存在 fdroid/local/，不入库）。
#
# 依赖：
#   - Python 3 + fdroidserver（推荐 pip install fdroidserver==2.4.5）
#   - Android SDK build-tools（提供 aapt/apksigner），或设置 ANDROID_HOME
#
# 用法：
#   FDROID_BIN=/path/to/fdroid ./fdroid/scripts/init-local-repo.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOCAL="$ROOT/fdroid/local"
FDROID_BIN="${FDROID_BIN:-fdroid}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$ANDROID_HOME" ]; then
  echo "请设置 ANDROID_HOME（需要 aapt/apksigner）" >&2
  exit 1
fi
if ! command -v "$FDROID_BIN" >/dev/null 2>&1; then
  echo "找不到 fdroid 命令：$FDROID_BIN" >&2
  exit 1
fi

mkdir -p "$LOCAL"
cd "$LOCAL"

if [ ! -f config.yml ] || [ ! -f keystore.p12 ]; then
  "$FDROID_BIN" init --no-prompt \
    --android-home "$ANDROID_HOME" \
    --keystore "$LOCAL/keystore.jks" \
    --repo-keyalias neuralstem-repo
fi

# 只改展示/URL 字段；私钥密码留在本地 config.yml，不要提交。
python3 - "$LOCAL/config.yml" <<'PY'
import re, sys
from pathlib import Path
p = Path(sys.argv[1])
s = p.read_text()
def put(key, value):
    global s
    pat = re.compile(rf'(?m)^#?\s*{re.escape(key)}:.*$')
    if pat.search(s):
        s = pat.sub(f'{key}: {value}', s, count=1)
    else:
        s += f'\n{key}: {value}\n'
put('repo_url', '"https://moefield.github.io/NeuralStem/fdroid/repo"')
put('repo_name', '"NeuralStem"')
put('repo_description', '"NeuralStem 自建源：离线分轨、播放与导出"')
p.write_text(s)
PY

mkdir -p metadata repo
cp "$ROOT/fdroid/metadata/pub.miku.neuralstem.yml" metadata/
echo "repo initialized: $LOCAL"
echo "备份：$LOCAL/keystore.jks 和 $LOCAL/config.yml（不要提交）"
