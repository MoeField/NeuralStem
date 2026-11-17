#!/bin/bash
# 把 fdroid/local/repo 发布到本仓库的 gh-pages 分支。
# 前置：仓库已启用 GitHub Pages（Settings → Pages → gh-pages / root）。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOCAL="$ROOT/fdroid/local"
TMP=$(mktemp -d)
trap 'git -C "$ROOT" worktree remove --force "$TMP" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT

if [ ! -d "$LOCAL/repo" ]; then
  echo "先运行 init-local-repo.sh 和 update-local-repo.sh" >&2
  exit 1
fi

git -C "$ROOT" worktree add --detach "$TMP" >/dev/null
git -C "$TMP" checkout gh-pages >/dev/null 2>&1 || {
  echo "没有 gh-pages 分支；先创建并推送一次" >&2
  exit 1
}

rm -rf "$TMP/fdroid/repo"
mkdir -p "$TMP/fdroid/repo"
cp -a "$LOCAL/repo/." "$TMP/fdroid/repo/"

git -C "$TMP" add .nojekyll fdroid/repo
if git -C "$TMP" diff --cached --quiet; then
  echo "repo 无变化"
  exit 0
fi
git -C "$TMP" -c user.name='MoeField' -c user.email='moe@example.invalid' \
  commit -m "publish: NeuralStem F-Droid repo"
git -C "$TMP" push origin gh-pages
echo "published gh-pages"
