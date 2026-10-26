#!/usr/bin/env bash
#
# fetch-avs3.sh —— 把 AVS3-P3 (Audio Vivid) 解码器源码拉到本地，用于自行编译。
#
# ============================================================================
# ⚠️ 请先读完这一段再运行
# ============================================================================
#
# ## 为什么不在仓库里
#
# AVS3-P3 解码器的参考实现（github.com/Dreamello/av3a）**不是** MIT/Apache/GPL，
# 而是《世界超高清视频产业联盟代码共享政策》
# （http://www.theuwa.com/UWA_Code_Sharing_Policy.pdf，中英文同等效力，冲突以中文为准）。
# 该政策的限制（已逐条核对原文）：
#
#   第二条   本政策**仅适用于联盟会员**向联盟贡献共享代码，
#            由联盟向**提出申请的会员**进行版权许可并发放代码。
#   第六条   除「有条件转发」外，**不能以任何形式进行转发**；
#            而「有条件转发」只允许会员把编译后的**目标代码**交给**自己的下游客户**，
#            且须告知客户不得反编译。
#   第七条   许可**不可转让、不可分许可**。
#   第八条   本政策**不包含专利权许可**（AVS3-P3 的实施另涉专利）。
#
# 本政策的「二次代码」定义还包含「与之链接（包含动态链接或静态链接）而形成的作品」，
# 所以既不能把源码放进仓库，也不能把它静态链接进自己的 .so 再发布。
#
# ⇒ 本脚本**不会**把任何上游代码提交进 git（目标目录已在 .gitignore 里）。
#   它只是帮你把这些文件放到本地构建目录，**能否合法使用由你自行判断**。
#   如果你不是 UWA 联盟会员、也不是某会员的下游客户，请注意合规风险。
#
# ============================================================================
# 用法
# ============================================================================
#
#   ./tools/fetch-avs3.sh              # 默认：拉源码（推荐，会自己编，绕开上游 .so 的 bug）
#   ./tools/fetch-avs3.sh --prebuilt   # 只拉预编译 .so（快，但 AArch64 上 decode 会崩）
#   ./tools/fetch-avs3.sh --clean      # 删除本地已拉取的文件
#
# 拉完之后：
#
#   ./gradlew app:assembleDebug -Pwith_ncm=true
#
# 构建产物是 libavs3self.so（自编译）或 libAVS3AudioDec.so（预编译），
# 桥会用 dlopen 按顺序尝试加载。
#
# ============================================================================

set -euo pipefail

# 仓库根目录（本脚本在 tools/ 下）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/app/src/main/cpp/avs3dec"
JNILIBS="${ROOT}/app/src/main/jniLibs/arm64-v8a"

UPSTREAM="https://github.com/Dreamello/av3a.git"
# 上游仓库 68MB，但真正需要的只有 av3adecoder/ 下的两个目录。
# 用 --filter=blob:none + sparse-checkout 只取需要的部分。

MODE="${1:-source}"

info()  { printf '\033[36m[avs3]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[avs3]\033[0m %s\n' "$*"; }
die()   { printf '\033[31m[avs3]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 清理

if [[ "$MODE" == "--clean" ]]; then
    info "删除本地 AVS3 文件（不影响 git 跟踪的其他内容）"
    rm -rf "$DEST"
    rm -f "$JNILIBS/libAVS3AudioDec.so" "$JNILIBS/libVMFPlatForm.so"
    info "完成。现在构建将不含 AVS3 解码能力（其他功能不受影响）。"
    exit 0
fi

# ---------------------------------------------------------------- 预检

command -v git >/dev/null || die "需要 git"

if [[ "$MODE" == "--prebuilt" ]]; then
    warn "=============================================================="
    warn "预编译版本的 libAVS3AudioDec.so 在 AArch64 上存在代码生成 bug："
    warn "  Avs3InitDecoder 内 0x3144 处 ldrsh [x25,#0x1202] 必崩"
    warn "  （详见 doc/.memory.md §8.5.3）"
    warn "建议用默认的源码模式（脚本不带参数）自己编译。"
    warn "=============================================================="
fi

# ---------------------------------------------------------------- 拉取

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

info "cloning ${UPSTREAM}（blobless + sparse，避免拉 68MB 全量）"
GIT_OPTS=()
# 直连不通时可走代理：如 export ALL_PROXY=socks5h://127.0.0.1:1080
if [[ -n "${ALL_PROXY:-}" ]]; then
    info "使用代理 $ALL_PROXY"
    GIT_OPTS=(-c "http.proxy=${ALL_PROXY}" -c "https.proxy=${ALL_PROXY}")
fi

git "${GIT_OPTS[@]}" clone --depth 1 --filter=blob:none --no-checkout \
    "$UPSTREAM" "$TMP/av3a" || die "clone 失败（网络问题？可试试 export ALL_PROXY=...）"

cd "$TMP/av3a"
git sparse-checkout init --no-cone
if [[ "$MODE" == "--prebuilt" ]]; then
    git sparse-checkout set "/ffmpeg/dependency/android/armv8-a/*"
else
    git sparse-checkout set \
        "/av3adecoder/avs3Decoder/src/*" \
        "/av3adecoder/avs3Decoder/include/*" \
        "/av3adecoder/libavs3_common/*"
fi
git "${GIT_OPTS[@]}" checkout || die "checkout 失败（拉 blob 出错，可重试）"

# ---------------------------------------------------------------- 落盘

if [[ "$MODE" == "--prebuilt" ]]; then
    mkdir -p "$JNILIBS"
    src="$TMP/av3a/ffmpeg/dependency/android/armv8-a"
    for f in libAVS3AudioDec.so libVMFPlatForm.so; do
        [[ -f "$src/$f" ]] && cp "$src/$f" "$JNILIBS/" && info "已放入 $f"
    done
    [[ -f "$JNILIBS/libAVS3AudioDec.so" ]] || die "上游没找到预编译库"
else
    rm -rf "$DEST"
    mkdir -p "$DEST/src" "$DEST/include" "$DEST/common"
    cp "$TMP/av3a/av3adecoder/avs3Decoder/src/"*.c        "$DEST/src/"
    cp "$TMP/av3a/av3adecoder/avs3Decoder/include/"*.h    "$DEST/include/"
    cp "$TMP/av3a/av3adecoder/libavs3_common/"*.c         "$DEST/common/"
    cp "$TMP/av3a/av3adecoder/libavs3_common/"*.h         "$DEST/common/"

    # avs3_debug.h 在 av3adecoder/libavs3_debug/ 下，但全工程只有
    # fd_spectrum_shaping_com.c 一个文件 include 它、且**一处 ASLOG_ 都没用**，
    # 所以补一个空占位头即可（省得为了一个没用到的 include 拉整个 debug 模块）。
    if [[ ! -f "$DEST/common/avs3_debug.h" ]]; then
        cat > "$DEST/common/avs3_debug.h" <<'EOF'
/*
 * 空占位头（由 tools/fetch-avs3.sh 生成）。
 *
 * 上游 av3adecoder/libavs3_debug/avs3_debug.h 提供桌面端日志/进程快照工具
 * （ASLOG_* 系列）。全工程只有 common/fd_spectrum_shaping_com.c 一个文件
 * #include 它，而且该文件里一处 ASLOG_ 都没用到 —— 是条无用的 include。
 *
 * 这里放空头：不改动上游文件，又能让编译通过。
 * 故意不定义任何宏：将来若真有人用 ASLOG_*，会因找不到符号而立刻暴露。
 */
#ifndef AVS3_DEBUG_H_LOCAL_STUB
#define AVS3_DEBUG_H_LOCAL_STUB
#endif
EOF
        info "已生成 avs3_debug.h 占位头"
    fi

    # Version.h 的两个 #ifdef 块在 Android 上会同时成立（NDK clang 为 aarch64
    # 目标预置了 linux 宏）→ VERSION_AVS3AudioDec_1_0_0_6 重定义，编译失败。
    # 这里把它改成两分支互斥的写法，语义不变。
    if grep -q '^#ifdef linux' "$DEST/include/Version.h" 2>/dev/null; then
        info "修补 Version.h（Android 上 linux 与 ARCH_AARCH64 同时成立导致重定义）"
        # 用 python 做，避免 sed 在不同平台上的 -i 差异
        python3 - "$DEST/include/Version.h" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8', errors='replace').read()
# 把第二个 "#ifdef ARCH_AARCH64" 改成 "#elif defined(ARCH_AARCH64)"，
# 并让第一个块覆盖两种平台：原来是 #ifdef linux / #ifdef ARCH_AARCH64 两个并列块。
s2 = s.replace('#ifdef linux', '#if defined(linux) || defined(ARCH_AARCH64)', 1)
s2 = s2.replace('#ifdef ARCH_AARCH64', '#elif 0 /* 已合并到上面的分支 */', 1)
if s2 != s:
    open(p, 'w', encoding='utf-8').write(s2)
    print('patched')
else:
    print('no change')
PY
    fi

    n_src=$(ls -1 "$DEST/src/"*.c 2>/dev/null | wc -l | tr -d ' ')
    n_common=$(ls -1 "$DEST/common/"*.c 2>/dev/null | wc -l | tr -d ' ')
    info "已落盘：src ${n_src} 个 .c，common ${n_common} 个 .c"
fi

# ---------------------------------------------------------------- 收尾

echo
info "=============================================================="
info "完成。这些文件**不会**进 git（app/src/main/cpp/avs3dec/ 已在 .gitignore）"
info ""
info "现在可以构建："
info "    ./gradlew app:assembleDebug -Pwith_ncm=true"
info ""
info "想撤销： ./tools/fetch-avs3.sh --clean"
info "=============================================================="
echo
warn "提醒：上游代码按 UWA 代码共享政策授权，仅联盟会员及其下游客户可合法使用。"
warn "      请自行确认你的使用场景是否合规；本项目不承担相关责任。"
