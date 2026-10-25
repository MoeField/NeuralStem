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
 * ## ★ 之前崩溃的根因（2026-10-25）
 * 靠反汇编猜签名时，把 `parse_header` 的第 4~6 参完forget全弄错了：
 * - 以为第4参是 `int* outOffset`，实际是 **`int isInitFrame`**
 * - 以为第5参是 `void* out2`，实际是 **`int* pnLenConsumed`**
 * - **完全漏了第6参 `unsigned short* crc`**
 *
 * ⇒ `parse_header` 往"不存在的" crc 指针写 → 破坏栈/堆，
 *   前 60 帧"碰巧"能跑，之后 `SIGSEGV fault addr 0x1202`。
 *
 * **教训：能拿到头文件就不要反汇编。**
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

/**
 * 加载解码库并解析符号。返回 0 成功，负数为错误码。
 *
 * 用 dlopen 而非直接链接：缺库时能给出明确错误，而不是进程启动崩溃。
 */
static int ensure_loaded(void) {
    if (g_ready) return 0;

    void *h = dlopen("libAVS3AudioDec.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        LOGE("dlopen(libAVS3AudioDec.so) failed: %s", dlerror());
        return -1;
    }
    p_create       = (fn_create)       dlsym(h, "avs3_create_decoder");
    p_destroy      = (fn_destroy)      dlsym(h, "avs3_destroy_decoder");
    p_parse_header = (fn_parse_header) dlsym(h, "parse_header");
    p_decode       = (fn_decode)       dlsym(h, "avs3_decode");
    p_decode_float = (fn_decode_float) dlsym(h, "avs3_decode_float");

    if (!p_create || !p_destroy || !p_parse_header || !p_decode) {
        LOGE("dlsym failed: create=%p destroy=%p parse_header=%p decode=%p",
             (void *) p_create, (void *) p_destroy,
             (void *) p_parse_header, (void *) p_decode);
        return -2;
    }
    g_ready = 1;
    LOGI("AVS3 loaded (float API %s)", p_decode_float ? "yes" : "no");
    return 0;
}

/* ==================== JNI 导出 ==================== */

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
 * ★初始化：官方正确流程（从 avs3_dec_lib.c 源码推导）
 *
 * ```c
 * // Avs3DecoderLibCreate:
 * Avs3ParseBsFrameHeader(hAvs3Dec, headerBs, isInitFrame=1, NULL);
 * Avs3InitDecoder(hAvs3Dec, &fModel, modelPath);
 * // Avs3DecoderLibParseHeader:
 * Avs3ParseBsFrameHeader(hAvs3Dec, headerBs, isInitFrame=0, &crcBs);
 * bytesPerFrame = ceil(bitsPerFrame / 8);
 * ```
 *
 * 对外就用 `parse_header(h, data, len, isInitFrame, &consumed, &crc)`：
 * - 第 1 次（isInitFrame=1）：解析头 + 初始化
 * - 第 2 次（isInitFrame=0）：算 CRC / bytesPerFrame
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

    LOGI("parse_header(len=%d init=%d) -> rc=%d consumed=%d crc=0x%04x",
         (int) len, (int) isInitFrame, rc, lenConsumed, (unsigned) crc);
    return rc;
}

/**
 * ★按官方顺序初始化解码器：先 parse_header(init=1)，再 parse_header(init=0)。
 *
 * @return 0 成功
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
    LOGI("init: parse_header(1) rc=%d consumed=%d", rc1, consumed1);

    /* 2) 再解析一次算 CRC / bytesPerFrame（isInitFrame=0） */
    int consumed2 = 0;
    unsigned short crc2 = 0;
    int rc2 = p_parse_header((void *) (uintptr_t) handle, data, (int) len, 0, &consumed2, &crc2);
    LOGI("init: parse_header(0) rc=%d consumed=%d crc=0x%04x", rc2, consumed2, (unsigned) crc2);

    (*env)->ReleaseByteArrayElements(env, headerFrame, buf, JNI_ABORT);

    if (rc1 != 0 || rc2 != 0) return -2;
    return 0;
}

/**
 * avs3_decode（按官方签名，**6 个参数**）。
 *
 * @return PCM 字节数；<=0 表示无输出或失败
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

    (*env)->ReleaseByteArrayElements(env, inBytes, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, outPcm, out, 0);

    if (rc != 1) {
        if (rc != 0) LOGW("avs3_decode rc=%d (inLen=%d)", rc, (int) inLen);
        return rc == 0 ? 0 : -1;
    }
    if (lenOut < 0 || lenOut > outCap) {
        LOGE("decode lenOut=%d > cap=%d", lenOut, (int) outCap);
        return -2;
    }
    jint info[2];
    info[0] = lenOut;
    info[1] = lenConsumed;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 2, info);
    return lenOut;
}
