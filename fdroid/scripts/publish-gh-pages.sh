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

# 始终基于远端最新 gh-pages，避免本地分支落后导致 non-fast-forward；
# CNAME/.nojekyll 等文件会被保留。
git -C "$ROOT" fetch origin gh-pages >/dev/null
git -C "$ROOT" worktree add --detach "$TMP" origin/gh-pages >/dev/null

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
git -C "$TMP" push origin HEAD:gh-pages
echo "published gh-pages"
