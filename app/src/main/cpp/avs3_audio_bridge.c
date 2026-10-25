/*
 * ★AVS3-P3 (av3a) 音频解码 JNI 桥。
 *
 * ## 背景
 * 网易云的 .ncm 里 98% 是 AVS3-P3 编码的 m4a，Android 原生不支持。
 * 2026-10-25 找到 `Dreamello/av3a` 仓库里有 **Android 预编译库**
 * （`libAVS3AudioDec.so` 324KB + `libVMFPlatForm.so` 185KB），无需自己编。
 *
 * ## API 从哪来的（没有头文件）
 * 仓库没给音频解码器的头文件，只能**反汇编导出符号**推出来：
 * ```
 * avs3_create_decoder:
 *     orr  w0, wzr, #0x1        ← calloc(1, 320)
 *     mov  w1, #0x140
 *     bl   calloc
 *     ret  x0                    ← 返回 opaque 指针，无参数
 *
 * avs3_decode:
 *     x0 = decoder
 *     x1 = input bytes
 *     w2 = input length
 *     x3 = output buffer
 *     x4 = int* outSize1   (先清零)
 *     x5 = int* outSize2   (先清零)
 *   → 返回 0=参数无效, 1=成功, 2=输入不足
 *   → *outSize1 = frameSize * channels * 2   (PCM16 字节数)
 *
 * avs3_destroy_decoder:
 *     x0 = decoder
 * ```
 *
 * ## 输出格式
 * `*outSize1 = (short)dec[0x2c] * (short)dec[0x4c] * 2`
 * ⇒ PCM 16-bit 交织，声道数 = dec[0x4c]，帧长 = dec[0x2c]。
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

/* ---- 从 libAVS3AudioDec.so 动态解析的函数指针 ---- */
typedef void *(*fn_create)(void);
typedef int   (*fn_decode)(void *dec, const void *in, int inLen,
                           void *out, int *outSize1, int *outSize2);
typedef void  (*fn_destroy)(void *dec);

static fn_create  p_create  = NULL;
static fn_decode  p_decode  = NULL;
static fn_destroy p_destroy = NULL;
static int        g_ready   = 0;

/* 一次解码最多输出的 PCM 字节数（按 8 声道 × 2048 帧 × 2B 估，留余量） */
#define MAX_PCM_BYTES (256 * 1024)

/* 输入暂存区：`avs3_decode` 内部会 memcpy 到自己的 buffer（用栈上 32KB） */
#define MAX_IN_BYTES  (64 * 1024)

/**
 * 加载解码库并解析符号。返回 0 成功，负数为错误码。
 *
 * 为何用 dlopen 而不是直接链接：
 * 仓库不给头文件，直接链接需要自己写 extern 声明 + 链接时按名字解析；
 * dlopen 更稳（缺库时能给出明确错误而不是进程启动崩溃）。
 */
static int ensure_loaded(void) {
    if (g_ready) return 0;

    void *h = dlopen("libAVS3AudioDec.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        // 有些设备需要绝对路径；先试 jniLibs 默认路径
        LOGE("dlopen(libAVS3AudioDec.so) failed: %s", dlerror());
        return -1;
    }
    p_create  = (fn_create)  dlsym(h, "avs3_create_decoder");
    p_decode  = (fn_decode)  dlsym(h, "avs3_decode");
    p_destroy = (fn_destroy) dlsym(h, "avs3_destroy_decoder");
    if (!p_create || !p_decode || !p_destroy) {
        LOGE("dlsym failed: create=%p decode=%p destroy=%p",
             (void *) p_create, (void *) p_decode, (void *) p_destroy);
        return -2;
    }
    g_ready = 1;
    LOGI("AVS3 decoder loaded (create=%p decode=%p)", (void *) p_create, (void *) p_decode);
    return 0;
}

/* ============ JNI 导出 ============ */

/** 解码器是否可用（库加载成功）。 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeInit(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return ensure_loaded();
}

/** 建解码器，返回 opaque handle（0 = 失败）。 */
JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (ensure_loaded() != 0) return 0;
    void *dec = p_create();
    LOGI("create decoder -> %p", dec);
    return (jlong) (uintptr_t) dec;
}

/** 销毁解码器。 */
JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle != 0 && p_destroy) {
        p_destroy((void *) (uintptr_t) handle);
        LOGI("destroy decoder %p", (void *) (uintptr_t) handle);
    }
}

/**
 * 解码一段码流。
 *
 * @return 解码出的 PCM 字节数；<0 表示失败（-1 参数错，-2 输入不足，-3 其它）
 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_experimental_ncm_Avs3AudioDecoder_nativeDecode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray inBytes, jint inLen,
        jbyteArray outPcm, jint outCap,
        jintArray outInfo) {
    (void) clazz;
    if (ensure_loaded() != 0) return -1;
    if (handle == 0 || inBytes == NULL || outPcm == NULL || outInfo == NULL) return -1;
    if (inLen <= 0 || inLen > MAX_IN_BYTES) return -1;

    jbyte *in = (*env)->GetByteArrayElements(env, inBytes, NULL);
    if (!in) return -1;
    jbyte *out = (*env)->GetByteArrayElements(env, outPcm, NULL);
    if (!out) {
        (*env)->ReleaseByteArrayElements(env, inBytes, in, JNI_ABORT);
        return -1;
    }

    int size1 = 0, size2 = 0;
    /* ★诊断：先打前 3 次调用的细节，看清 rc 与输入长度 */
    static int dbgCnt = 0;
    int doLog = (dbgCnt < 3);
    if (doLog) {
        LOGI("decode call#%d: dec=%p inLen=%d outCap=%d", dbgCnt, (void*)(uintptr_t)handle, inLen, outCap);
    }
    int rc = p_decode((void *) (uintptr_t) handle, in, inLen, out, &size1, &size2);
    if (doLog) {
        LOGI("decode rc=%d size1=%d size2=%d", rc, size1, size2);
        dbgCnt++;
    }

    (*env)->ReleaseByteArrayElements(env, inBytes, in, JNI_ABORT);
    // 输出要提交回去（0 = 复制回并释放）
    (*env)->ReleaseByteArrayElements(env, outPcm, out, 0);

    if (rc != 1) {
        LOGW("avs3_decode rc=%d (inLen=%d)", rc, inLen);
        return rc == 2 ? -2 : -1;
    }
    if (size1 < 0 || size1 > outCap) {
        LOGE("decoded size1=%d exceeds capacity=%d", size1, outCap);
        return -3;
    }

    // outInfo = [pcmBytes, frameBytes(=channels*2)]
    jint info[2];
    info[0] = size1;
    info[1] = size2;
    (*env)->SetIntArrayRegion(env, outInfo, 0, 2, info);

    return size1;
}
