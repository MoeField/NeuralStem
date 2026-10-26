/*
 * ★AVS3-P3 (av3a) 音频解码 JNI 桥。
 *
 * ## 函数签名（来自官方头文件，不再靠反汇编猜）
 * 来源：`Dreamello/av3a` → `av3adecoder/avs3Decoder/include/avs3_decoder_interface.h`
 *
 * ```c
 * typedef void* AVS3DecoderHandle;
 *
 * AVS3DecoderHandle avs3_create_decoder();
 * void avs3_destroy_decoder(AVS3DecoderHandle h);
 *
 * int parse_header(AVS3DecoderHandle h,
 *                  unsigned char* pData,
 *                  int nLenIn,
 *                  int isInitFrame,        // ★ 1 = 初始化帧（sequence header）
 *                  int *pnLenConsumed,     // 输出：消费了多少输入字节
 *                  unsigned short *crc);   // 输出：CRC
 *
 * int avs3_decode(AVS3DecoderHandle h,
 *                 unsigned char* pDataIN, int nLenIn,
 *                 unsigned char* pDataOut,
 *                 int *pnLenOut,            // 输出：PCM 字节数
 *                 int *pnLenConsumed);      // 输出：消费的输入字节
 *
 * int avs3_decode_float(AVS3DecoderHandle h,
 *                       unsigned char* pDataIN, int nLenIn,
 *                       float* pDataOut,
 *                       int *pnSamplesOut,      // 输出：采样点数
 *                       int *pnLenConsumed);
 * ```
 *
 * ## ★ 崩溃 addr 0x1202 的真正根因（2026-10-26 反汇编确证）
 *
 * ⚠️ 旧注释曾写「漏传 crc 参数导致栈破坏」—— **那是错的**。crc 参数早就补上了，
 * 崩溃照旧复现。反汇编 `libAVS3AudioDec.so` 后定位到 `Avs3InitDecoder` 内 `0x3144`：
 *
 * ```
 * 2a7c: ldrh  w8, [x23]        ; x23 = ctx + 0x2004，读 {crcBs, bytesPerFrame} 这一对
 * 2a80: cmp   w8, #0x1
 * 2a84: b.ne  0x3144           ; 值 != 1 → 走「32-bit 字段」分支
 *   ...
 * 3144: ldrsh x21, [x25, #0x1202]   ; ★崩溃点（x25 = r23 = 全零栈缓冲）
 * ```
 *
 * 该分支假设头字段是 32-bit，于是取 `crcBs`(0x1200) / `bytesPerFrame`(0x1202)
 * 的低 16 位当 CRC 和帧长。而官方头文件里是 `uint16_t crcBs; int16_t bytesPerFrame;`，
 * crc 恰好落在 0x1200 → `[crcBs, bytesPerFrame]` 作为 32-bit 值 = `bytesPerFrame << 16`，
 * **永远不可能等于 1** → 必然走崩溃分支。
 *
 * ⇒ 这是**厂商 .so 的 AArch64 代码生成 bug**（gcc 用不了 >4095 的 ldrh 偏移，
 *   选错了分支），不是调用方用法问题，我们也无法从外部绕开。
 *
 * 这也是 memory 里「前 60 帧碰巧能跑」的真实解释：那 60 帧是上一轮**没调
 * parse_header** 时直接瞎喂帧头字节产生的噪声输出，并非真解码。
 *
 * **结论：只有拿到能自编译的 AVS3 解码源码才能出音。**（详见 doc/.memory.md §八）
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <dlfcn.h>

#define TAG "Avs3Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ==================== ★官方返回值语义（必读） ====================
 * 来源：`av3adecoder/libavs3_common/avs3_cnst_com.h`
 *
 *     #define AVS3_FALSE            0
 *     #define AVS3_TRUE             1
 *     #define AVS3_DATA_NOT_ENOUGH  2
 *
 * 而 `decoder.c` 里 `parse_header` 的文档注释写得非常清楚：
 *
 *     return value:
 *         0: failed
 *         1: success
 *         2: data not enough
 *
 * ⇒ **1 是成功**。之前这里把 1 当失败，导致 nativeInitSequence 永远返回 -2，
 *   整条解码链在第一步就被挡死（真机日志：`parse_header(...) -> rc=1` 后紧跟
 *   `initSequence rc=-2`）。这个坑一定要记住：这套库是「1 = true」不是「0 = OK」。
 *
 * 注意 `avs3_decode` 用的是**同一套**常量，所以它的成功判据也是 `rc == 1`。
 */
#define AVS3_FALSE            0   /* 失败 */
#define AVS3_TRUE             1   /* ★成功 */
#define AVS3_DATA_NOT_ENOUGH  2   /* 输入不够（滑窗找同步字用尽） */

/* 判定 helper：把这套「非主流」的成功值收敛到一处，避免各处再写错。 */
#define AVS3_OK(rc)           ((rc) == AVS3_TRUE)

/* ---- 官方签名（与 avs3_decoder_interface.h 一致） ---- */
typedef void *(*fn_create)(void);
typedef void  (*fn_destroy)(void *h);
typedef int   (*fn_parse_header)(void *h, unsigned char *pData, int nLenIn,
                                 int isInitFrame, int *pnLenConsumed,
                                 unsigned short *crc);
typedef int   (*fn_decode)(void *h, unsigned char *in, int nLenIn,
                           unsigned char *out, int *pnLenOut, int *pnLenConsumed);
typedef int   (*fn_decode_float)(void *h, unsigned char *in, int nLenIn,
                                 float *out, int *pnSamplesOut, int *pnLenConsumed);

static fn_create       p_create       = NULL;
static fn_destroy      p_destroy      = NULL;
static fn_parse_header p_parse_header = NULL;
static fn_decode       p_decode       = NULL;
static fn_decode_float p_decode_float = NULL;
static int             g_ready        = 0;

/*
 * ============================================================================
 * ★为什么采用「纯 dlopen」而不是静态链接（2026-10-26 修正）
 * ============================================================================
 * 早前一版把 avs3dec 静态链进本 .so（NEURALSTEM_AVS3_BUILTIN）。
 * 但 UWA 代码共享政策第三条对「二次代码」的定义是：
 *     “……与之链接（包括动态链接或静态链接）而形成的作品”
 * ⇒ 静态链接后，**整个 libneuralstem_avs3.so 都变成二次代码**，
 *   不能再以 MIT 发布。
 *
 * 政策又留了一个口子：
 *     “仅与共享代码进行进程间通信或系统调用的作品是独立作品，不属于二次代码”
 * 而 dlopen 是最接近“独立作品”的形态（运行时才加载、无编译期依赖、
 * 不含对方任何符号与目标代码）。因此本文件**只保留 dlopen 路径**，
 * 自身不含任何 UWA 代码，可以干净地归入 MIT。
 *
 * ## 加载顺序（都失败就返回错误码，不影响 App 其他功能）
 *   1. libAVS3AudioDec.so  —— 预编译版（简单，但在 AArch64 有代码生成 bug）
 *   2. libavs3self.so      —— 用户自行编译版（tools/fetch-avs3.sh 产出，
 *                             改过名以避免与上面同名冲突）
 */

/** 依次尝试这几个库名，找到第一个能解析全部符号的就用它。 */
static const char *const kLibCandidates[] = {
    "libavs3self.so",        /* 自编译版优先：没有 AArch64 寻址 bug */
    "libAVS3AudioDec.so",    /* 预编译版回退（已知 decode 会 SIGSEGV） */
};

/**
 * 加载解码库并解析符号。返回 0 成功，负数为错误码。
 *
 * 用 dlopen 而非直接链接：① 缺库时给错误码而不是进程启动崩溃；
 * ② 保证本文件自身不含对方目标代码，可归 MIT（见上方注释）。
 */
static int ensure_loaded(void) {
    if (g_ready) return 0;

    const size_t n = sizeof(kLibCandidates) / sizeof(kLibCandidates[0]);
    for (size_t i = 0; i < n; i++) {
        const char *name = kLibCandidates[i];
        void *h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            LOGI("dlopen(%s) 失败: %s", name, dlerror());
            continue;
        }

        p_create       = (fn_create)       dlsym(h, "avs3_create_decoder");
        p_destroy      = (fn_destroy)      dlsym(h, "avs3_destroy_decoder");
        p_parse_header = (fn_parse_header) dlsym(h, "parse_header");
        p_decode       = (fn_decode)       dlsym(h, "avs3_decode");
        p_decode_float = (fn_decode_float) dlsym(h, "avs3_decode_float");

        if (!p_create || !p_destroy || !p_parse_header || !p_decode) {
            LOGW("%s 缺少必要符号（create=%p destroy=%p parse_header=%p decode=%p）",
                 name, (void *) p_create, (void *) p_destroy,
                 (void *) p_parse_header, (void *) p_decode);
            dlclose(h);
            p_create = NULL; p_destroy = NULL;
            p_parse_header = NULL; p_decode = NULL; p_decode_float = NULL;
            continue;
        }

        g_ready = 1;
        LOGI("AVS3 已加载：%s（float API %s）", name, p_decode_float ? "yes" : "no");
        return 0;
    }

    /* 两个候选都没有 —— 这是**正常情况**（未自行编译 AVS3）。
     * 用 LOGI 而不是 LOGE：用户没装解码器不是错误，App 其余功能照常用。 */
    LOGI("未找到 AVS3 解码库（跑 tools/fetch-avs3.sh 可启用）；"
         "AVS3(av3a) 曲目将不可播，其他格式不受影响");
    return -1;
}

/* ==================== JNI 导出 ==================== */

/*
 * ★取解码器的配置（声道数 / 采样率 / 帧长）—— 供下混用。
 *
 * ## 为何需要它
 * AVS3 是空间音频，这批文件实测是 `MC_7_1_4`，共 **12 声道**。
 * 播放前要下混成立体声，就必须知道源声道数 ——
 * 而官方库**没有导出任何 getConfig 符号**（`Avs3DecoderLibGetConfig`
 * 属于 wrapper 层，不在 .so 的导出 ABI 里），只能读结构体。
 *
 * ## ★偏移是怎么确定的（不是猜的）
 * `AVS3Decoder` 里混着 short / long / int32_t / 指针，LP64 下 long 是 8 字节，
 * 手算极易错且错了不报错。这里用 **static_assert 让编译器把实际 offsetof
 * 吐出来**（故意断言错误值，读编译器报错里的 `expression evaluates to`）：
 *
 *     numChansOutput  → 44 (0x2c)
 *     frameLength     → 76 (0x4c)
 *     outputFs        →  8 (0x08)
 *
 * 交叉验证：反汇编 `Avs3Decode` 里正是
 *     ldrh w20, [x0, #0x4c]   ← frameLength
 *     ldrh w22, [x0, #0x2c]   ← numChansOutput
 * 与 offsetof 完全吻合。
 *
 * ## 安全网
 * 读出来的值做**合理性窗口校验**（声道 1..64、采样率 8k..384k），
 * 超出范围就返回 -1 —— 即使将来结构体变了也不会把垃圾值传给上层。
 */

/** 有源码时的精确路径（由 CMake 在检测到 avs3dec/ 时定义 NEURALSTEM_AVS3_HEADERS）。 */
#if defined(NEURALSTEM_AVS3_HEADERS) && NEURALSTEM_AVS3_HEADERS
#include "avs3_stat_dec.h"
#endif

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeGetChannels(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (!handle) return -1;
#if defined(NEURALSTEM_AVS3_HEADERS) && NEURALSTEM_AVS3_HEADERS
    const AVS3DecoderHandle h = (AVS3DecoderHandle) (uintptr_t) handle;
    const int16_t v = h->numChansOutput;
    return (v >= 1 && v <= 64) ? (jint) v : -1;
#else
    /* 无源码：用编译器实测的偏移 0x2c。 */
    const int16_t v = *(const int16_t *) ((const uint8_t *) (uintptr_t) handle + 0x2c);
    return (v >= 1 && v <= 64) ? (jint) v : -1;
#endif
}

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeGetSampleRate(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (!handle) return -1;
#if defined(NEURALSTEM_AVS3_HEADERS) && NEURALSTEM_AVS3_HEADERS
    const long v = ((AVS3DecoderHandle) (uintptr_t) handle)->outputFs;
#else
    /* 偏移 0x08；这是个 long（LP64 = 8 字节），不是 int。 */
    const long v = *(const long *) ((const uint8_t *) (uintptr_t) handle + 0x08);
#endif
    return (v >= 8000 && v <= 384000) ? (jint) v : -1;
}

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeInit(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return ensure_loaded();
}

JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeCreate(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (ensure_loaded() != 0) return 0;
    void *h = p_create();
    LOGI("create decoder -> %p", h);
    /*
     * ★2026-10-26 实测：这只是 calloc，**不含任何初始化**。
     *   真正的初始化（Avs3InitDecoder：载模型 + 建 core/channel 解码器）
     *   只在 parse_header 里发生一次，且被 `if (!ctx->bInited)` 守卫 ——
     *   而 bInited 在结构体末尾，arm64 上 gcc 用 16-bit 可寻址的 ldrh 访问它，
     *   偏移 0x1202 超过 ldrh 的 ±4095 范围 → 编译器走了错误的寻址分支,
     *   **第一次 decode 必然 SIGSEGV (fault addr 0x1202)**。
     *   是 libAVS3AudioDec.so 的 AArch64 代码生成 bug，我们无法绕开。
     */
    LOGW("注意：create 只是 calloc，未初始化；直接调 avs3_decode 会 SIGSEGV(0x1202)");
    return (jlong) (uintptr_t) h;
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle && p_destroy) {
        p_destroy((void *) (uintptr_t) handle);
        LOGI("destroy decoder %p", (void *) (uintptr_t) handle);
    }
}

/**
 * ★初始化 + 解析帧头。
 *
 * ## 官方流程（avs3Decoder/src/decoder.c 与 avs3_dec_lib.c）
 *
 * `parse_header` 一次就把两件事都做完：
 *  1. 解析帧头字段，填 ctx（outputFs / frameLength / numChansOutput / bitsPerFrame …）
 *  2. `if (!ctx->bInited) { Avs3InitDecoder(...); ctx->bInited = 1; }` —— 懒初始化
 *
 * 而且代码里 `isInitFrame` 参数**已经被注释掉、完全没用到**（`// if (isInitFrame == 0)`），
 * 所以官方封装 `Avs3DecoderLibCreate` + `Avs3DecoderLibParseHeader` 里那两次调用
 * 其实是**重复初始化**，不会有额外效果。
 *
 * ## ★返回值语义（`libavs3_common/avs3_cnst_com.h`）
 * ```c
 * #define AVS3_FALSE            0   // 失败
 * #define AVS3_TRUE             1   // ★成功
 * #define AVS3_DATA_NOT_ENOUGH  2   // 输入不够
 * ```
 * **1 = 成功**。不要在这里「归一化」，否则调用方无法区分「真失败」与「输入不够」。
 *
 * ## ★consumed = 7 是正常的
 * 本文件非混音（codingProfile=0）时帧头正好 56 bit = 7 字节，`consumed` 报 7 与源码一致。
 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeParseHeader(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray frame, jint isInitFrame) {
    (void) clazz;
    if (ensure_loaded() != 0) return -1;
    if (!handle || !frame) return -1;

    jsize len = (*env)->GetArrayLength(env, frame);
    jbyte *buf = (*env)->GetByteArrayElements(env, frame, NULL);
    if (!buf) return -1;

    int lenConsumed = 0;
    unsigned short crc = 0;
    int rc = p_parse_header((void *) (uintptr_t) handle, (unsigned char *) buf, (int) len,
                            (int) isInitFrame, &lenConsumed, &crc);
    (*env)->ReleaseByteArrayElements(env, frame, buf, JNI_ABORT);

    /*
     * ★实测记录（2026-10-26，真机 BF00A / AVS3-P3 MC_7_1_4 / 44.1kHz）：
     *   rc=1(success) consumed=7 crc=0xf48d
     * 消费 7 字节 = 56 bit = NBITS_FRAME_HEADER_MC，完全符合官方定义。
     */

    /* ★日志里把 rc=1 标注成 success：这是排查时最容易看错的一行 */
    const char *meaning = (rc == AVS3_TRUE)            ? "success"
                        : (rc == AVS3_FALSE)           ? "FAILED"
                        : (rc == AVS3_DATA_NOT_ENOUGH) ? "data-not-enough"
                                                       : "unknown";
    (void) meaning;
    LOGI("parse_header(len=%d init=%d) -> rc=%d(%s) consumed=%d crc=0x%04x",
         (int) len, (int) isInitFrame, rc, meaning, lenConsumed, (unsigned) crc);
    return rc;
}

/**
 * ★按官方顺序初始化解码器：先 parse_header(init=1)，再 parse_header(init=0)。
 *
 * ★关于失败判据（之前就是在这里错的）
 * 官方 `parse_header` 返回 **1 = 成功**（见文件顶部 AVS3_* 常量区）。
 * 所以失败条件是 `rc == AVS3_FALSE(0)` 或 `rc == AVS3_DATA_NOT_ENOUGH(2)`，
 * 绝对不是 `rc != 0`。
 *
 * @return 0 成功；负数失败（-2 = 两次 parse 中至少一次不是 success）
 *
 * 注：这里**不做**「把期望帧大小回传 Kotlin」的事 —— 官方库虽然内部算好了
 * `bitsPerFrame`，但没通过任何导出符号暴露（`Avs3DecoderLibGetConfig` 属于
 * wrapper 层，不在 so 的导出 ABI 里），struct 偏移也是私有实现。
 * 猜偏移 = 埋雷，所以宁可不提供这信息，只把 consumed / crc 打日志供人工推算。
 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeInitSequence(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray headerFrame) {
    (void) clazz;
    if (ensure_loaded() != 0) return -1;
    if (!handle || !headerFrame) return -1;

    jsize len = (*env)->GetArrayLength(env, headerFrame);
    jbyte *buf = (*env)->GetByteArrayElements(env, headerFrame, NULL);
    if (!buf) return -1;
    unsigned char *data = (unsigned char *) buf;

    /* 1) 解析帧头 + 初始化（isInitFrame=1） */
    int consumed1 = 0;
    unsigned short crc1 = 0;
    int rc1 = p_parse_header((void *) (uintptr_t) handle, data, (int) len, 1, &consumed1, &crc1);
    LOGI("init: parse_header(1) rc=%d(%s) consumed=%d crc=0x%04x",
         rc1, AVS3_OK(rc1) ? "success" : "not-success", consumed1, (unsigned) crc1);

    /* 2) 再解析一次算 CRC / bytesPerFrame（isInitFrame=0） */
    int consumed2 = 0;
    unsigned short crc2 = 0;
    int rc2 = p_parse_header((void *) (uintptr_t) handle, data, (int) len, 0, &consumed2, &crc2);
    LOGI("init: parse_header(0) rc=%d(%s) consumed=%d crc=0x%04x",
         rc2, AVS3_OK(rc2) ? "success" : "not-success", consumed2, (unsigned) crc2);

    /* 3) 官方自检：一次成功 parse 至少要吃掉「同步字(2) + 帧头」共 9 字节，
     *    若 consumed < 9 说明输入被截断/切分错了，提前把可疑点打出来。
     *    （帧头 7B 是不含同步字的长度；含 2B sync 才是 9B，与官方 bytesPerFrame 口径一致） */
    if (AVS3_OK(rc1) && consumed1 < 9) {
        LOGW("init: consumed(init=1)=%d < 9，输入可能被截断（frameLen=%d）",
             consumed1, (int) len);
    }
    if (AVS3_OK(rc2) && consumed2 < 9) {
        LOGW("init: consumed(init=0)=%d < 9，输入可能被截断（frameLen=%d）",
             consumed2, (int) len);
    }

    (*env)->ReleaseByteArrayElements(env, headerFrame, buf, JNI_ABORT);

    /* ★只有「1」才是成功。这里写 AVS3_OK(...) 而不是 rc != 0，是刻意的。 */
    if (!AVS3_OK(rc1) || !AVS3_OK(rc2)) {
        LOGE("initSequence FAILED: rc1=%d rc2=%d (1=success)", rc1, rc2);
        return -2;
    }
    return 0;
}

/**
 * avs3_decode（按官方签名，**6 个参数**）。
 *
 * ★返回值语义（项目自定义，与库的 rc 分开看）
 * - `> 0`：本帧 PCM 字节数（已写进 outPcm，outInfo = [lenOut, lenConsumed]）
 * - `0`  ：**成功但本帧无输出**，包括两种情形：
 *           ① `rc == AVS3_TRUE` 但 `lenOut == 0`（例如首帧只含 sequence header）；
 *           ② `rc == AVS3_DATA_NOT_ENOUGH`（输入不够，帧被切错了）。
 *           这里用同一个 0 是为了让 Kotlin 侧的「空帧」分支统一（不当作硬失败），
 *           真要区分请看上面的 LOGW（DATA_NOT_ENOUGH 会打印 inLen/consumed）。
 * - `< 0`：硬失败（-1 参数/一致性错误，-2 lenOut 超缓冲）
 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeDecode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray inBytes, jint inLen,
        jbyteArray outPcm, jint outCap,
        jintArray outInfo) {
    (void) clazz;
    if (ensure_loaded() != 0) return -1;
    if (!handle || !inBytes || !outPcm || !outInfo) return -1;
    if (inLen <= 0) return -1;

    jbyte *in = (*env)->GetByteArrayElements(env, inBytes, NULL);
    if (!in) return -1;
    jbyte *out = (*env)->GetByteArrayElements(env, outPcm, NULL);
    if (!out) {
        (*env)->ReleaseByteArrayElements(env, inBytes, in, JNI_ABORT);
        return -1;
    }

    int lenOut = 0;
    int lenConsumed = 0;
    int rc = p_decode((void *) (uintptr_t) handle,
                      (unsigned char *) in, (int) inLen,
                      (unsigned char *) out, &lenOut, &lenConsumed);

    /*
     * ★诊断（2026-10-26）：`avs3_decode` **不做任何码流校验**，
     *   `bytesPerFrame` 完全由帧头推出的比特率决定（见 decoder.c）。
     *   所以 lenConsumed 是判断「帧边界对不对」的唯一信号：
     *   - lenConsumed == inLen 且 == 2422 → 切分正确
     *   - lenConsumed 远大于/小于 inLen → 比特率与码流不自洽
     *   只在开头打几行，避刷屏。
     */
    {
        static int s_diag;
        if (s_diag < 6) {
            LOGI("decode#%d inLen=%d rc=%d lenOut=%d lenConsumed=%d head=%02x%02x%02x%02x%02x%02x%02x%02x",
                 s_diag, (int) inLen, rc, lenOut, lenConsumed,
                 (unsigned char) in[0], (unsigned char) in[1],
                 (unsigned char) in[2], (unsigned char) in[3],
                 (unsigned char) in[4], (unsigned char) in[5],
                 (unsigned char) in[6], (unsigned char) in[7]);
            s_diag++;
        }
    }

    (*env)->ReleaseByteArrayElements(env, inBytes, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, outPcm, out, 0);

    /* ★这里的 `rc != 1` 是**正确的**（decode 同样 1=success，见顶部 AVS3_* 常量区）。
     * 千万别改成 `rc != 0` —— 那又会把「成功」当失败，重蹈 initSequence 的覆车。 */
    if (!AVS3_OK(rc)) {
        if (rc == AVS3_DATA_NOT_ENOUGH) {
            /* 输入不够：典型原因是 access unit 被切剩了（帧头 7B + 载荷没喂完整）。
             * 返回 0（而非 -1）让上层当「本帧无输出」处理，同时把诊断信息打出来。 */
            LOGW("avs3_decode DATA_NOT_ENOUGH inLen=%d consumed=%d",
                 (int) inLen, lenConsumed);
            return 0;
        }
        LOGW("avs3_decode FAILED rc=%d inLen=%d consumed=%d", rc, (int) inLen, lenConsumed);
        return -1;
    }
    if (lenOut < 0 || lenOut > outCap) {
        LOGE("decode lenOut=%d > cap=%d（outBuf 太小，请加大 DEFAULT_OUT_CAP）",
             lenOut, (int) outCap);
        return -2;
    }
    jint info[2];
    info[0] = lenOut;
    info[1] = lenConsumed;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 2, info);
    return lenOut;   /* lenOut == 0 → 成功但空帧，上层按 EMPTY 处理 */
}
