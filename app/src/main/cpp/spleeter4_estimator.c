// 阶段 M4 / S3：Spleeter 4-stem **估计器**（自写 JNI，句柄式，复用 ORT 会话）。
//
// 与 `spleeter4_probe.c`（一次性探针）的区别：这里**建 4 个 ORT 会话并持有**，
// 供分块/多段音频反复调用，避免每块重建会话。
//
// 契约（一手核对自 ONNX 元数据；桌面已验证 -163.5 dB，见 .memory.md §M4-S3）：
//   IN  x : [2, num_splits, 512, 1024]   幅度谱（1024 of 2049 bins）
//   OUT y : [2, num_splits, 512, 1024]   该 stem 的幅度估计（ELU）
//
// 本层只做「幅度谱进 → 4 个幅度估计出」；mask / 带扩展 / iSTFT 在 Kotlin（Spleeter4Dsp）。
//
// ★ 教训（S2 坑 48）：所有 ORT 句柄必须初始化；Run 前显式判空；否则 UB 崩溃会被误判为库 bug。
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "onnxruntime_c_api.h"

#define TAG "Spleeter4Est"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NUM_STEMS  4
#define BINS       1024
#define SPLIT_FRAMES 512
#define NCH        2

static const char *STEM_NAMES[NUM_STEMS] = {"vocals", "drums", "bass", "other"};

// 句柄：一个 env + 4 个会话（+ 共享 options/meminfo）
typedef struct {
    const OrtApi *api;
    OrtEnv *env;
    OrtSessionOptions *opts;
    OrtMemoryInfo *mem;
    OrtSession *sessions[NUM_STEMS];
} Sp4Handle;

// ---- create ----

JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_spike_Spleeter4Estimator_nativeCreate(
    JNIEnv *env, jclass clazz, jstring j_dir, jint threads) {
    const char *dir = (*env)->GetStringUTFChars(env, j_dir, NULL);
    const OrtApiBase *base = OrtGetApiBase();
    const OrtApi *api = base ? base->GetApi(ORT_API_VERSION) : NULL;
    if (!api) {
        LOGE("GetApi(%d) null", ORT_API_VERSION);
        (*env)->ReleaseStringUTFChars(env, j_dir, dir);
        return 0;
    }

    Sp4Handle *h = (Sp4Handle *)calloc(1, sizeof(Sp4Handle));
    if (!h) { (*env)->ReleaseStringUTFChars(env, j_dir, dir); return 0; }
    h->api = api;

    OrtStatus *st = NULL;
    st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "sp4", &h->env);
    if (st) { LOGE("CreateEnv: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }
    st = api->CreateSessionOptions(&h->opts);
    if (st) { LOGE("CreateSessionOptions: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }
    api->SetIntraOpNumThreads(h->opts, threads > 0 ? threads : 4);
    st = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &h->mem);
    if (st) { LOGE("CreateCpuMemoryInfo: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }

    for (int s = 0; s < NUM_STEMS; s++) {
        char path[512];
        snprintf(path, sizeof(path), "%s/%s.fp16.onnx", dir, STEM_NAMES[s]);
        st = api->CreateSession(h->env, path, h->opts, &h->sessions[s]);
        if (st || !h->sessions[s]) {
            LOGE("CreateSession(%s) failed: %s", STEM_NAMES[s], st ? api->GetErrorMessage(st) : "null");
            if (st) api->ReleaseStatus(st);
            goto fail;
        }
        LOGI("session %s ready", STEM_NAMES[s]);
    }

    (*env)->ReleaseStringUTFChars(env, j_dir, dir);
    LOGI("estimator created (4 sessions), threads=%d", threads);
    return (jlong)(intptr_t)h;

fail:
    (*env)->ReleaseStringUTFChars(env, j_dir, dir);
    // 逐项释放已建资源（★ 不释放未建者）
    if (h) {
        for (int s = 0; s < NUM_STEMS; s++) if (h->sessions[s]) api->ReleaseSession(h->sessions[s]);
        if (h->mem) api->ReleaseMemoryInfo(h->mem);
        if (h->opts) api->ReleaseSessionOptions(h->opts);
        if (h->env) api->ReleaseEnv(h->env);
        free(h);
    }
    return 0;
}

// ---- estimate ----
//
// in : jmag, 长度 = NCH * splits * SPLIT_FRAMES * BINS（行优先）
// out: jfloatArray[NUM_STEMS][NCH * splits * SPLIT_FRAMES * BINS]，每个 stem 一个数组
JNIEXPORT jobjectArray JNICALL
Java_pub_miku_neuralstem_spike_Spleeter4Estimator_nativeEstimate(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray j_mag, jint splits) {
    Sp4Handle *h = (Sp4Handle *)(intptr_t)handle;
    if (!h || splits <= 0) { LOGE("invalid handle/splits"); return NULL; }
    const OrtApi *api = h->api;

    const jsize inLen = (jsize)((size_t)NCH * splits * SPLIT_FRAMES * BINS);
    if ((*env)->GetArrayLength(env, j_mag) != inLen) {
        LOGE("mag len %d != expected %d", (int)(*env)->GetArrayLength(env, j_mag), (int)inLen);
        return NULL;
    }

    // 找到 jfloatArray 类（构造返回数组用）
    jclass floatArrCls = (*env)->FindClass(env, "[F");
    if (!floatArrCls) { LOGE("FindClass [F failed"); return NULL; }
    jobjectArray result = (*env)->NewObjectArray(env, NUM_STEMS, floatArrCls, NULL);
    if (!result) return NULL;

    float *mag = (float *)malloc(sizeof(float) * inLen);
    if (!mag) { LOGE("malloc mag failed"); return NULL; }
    (*env)->GetFloatArrayRegion(env, j_mag, 0, inLen, mag);

    const int64_t shapeIn[4] = {NCH, splits, SPLIT_FRAMES, BINS};
    const char *inNames[1] = {"x"};
    const char *outNames[1] = {"y"};

    for (int s = 0; s < NUM_STEMS; s++) {
        OrtValue *inVal = NULL;
        const OrtValue *outputs[1] = {NULL};
        OrtStatus *st = api->CreateTensorWithDataAsOrtValue(
            h->mem, mag, (size_t)inLen * sizeof(float), shapeIn, 4,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inVal);
        if (st || !inVal) {
            LOGE("CreateTensor(%s): %s", STEM_NAMES[s], st ? api->GetErrorMessage(st) : "null");
            if (st) api->ReleaseStatus(st);
            api->ReleaseValue(inVal);
            continue;   // 该 stem 失败 → 结果槽留 null
        }

        st = api->Run(h->sessions[s], NULL, inNames,
                      (const OrtValue *const *)&inVal, 1, outNames, 1, (OrtValue **)outputs);
        api->ReleaseValue(inVal);
        if (st || !outputs[0]) {
            LOGE("Run(%s): %s", STEM_NAMES[s], st ? api->GetErrorMessage(st) : "null out");
            if (st) api->ReleaseStatus(st);
            continue;
        }

        // 校验输出元素数并拷回 Java
        OrtTensorTypeAndShapeInfo *ti = NULL;
        size_t count = 0;
        api->GetTensorTypeAndShape(outputs[0], &ti);
        api->GetTensorShapeElementCount(ti, &count);
        api->ReleaseTensorTypeAndShapeInfo(ti);
        if (count != (size_t)inLen) {
            LOGE("unexpected out count %zu != %d", count, (int)inLen);
            api->ReleaseValue(outputs[0]);
            continue;
        }

        float *outData = NULL;
        api->GetTensorMutableData(outputs[0], (void **)&outData);
        if (outData) {
            jfloatArray arr = (*env)->NewFloatArray(env, inLen);
            if (arr) {
                (*env)->SetFloatArrayRegion(env, arr, 0, inLen, outData);
                (*env)->SetObjectArrayElement(env, result, s, arr);
            }
        }
        api->ReleaseValue(outputs[0]);
    }

    free(mag);
    return result;
}

// ---- release ----

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_spike_Spleeter4Estimator_nativeRelease(
    JNIEnv *env, jclass clazz, jlong handle) {
    Sp4Handle *h = (Sp4Handle *)(intptr_t)handle;
    if (!h) return;
    const OrtApi *api = h->api;
    for (int s = 0; s < NUM_STEMS; s++) if (h->sessions[s]) api->ReleaseSession(h->sessions[s]);
    if (h->mem) api->ReleaseMemoryInfo(h->mem);
    if (h->opts) api->ReleaseSessionOptions(h->opts);
    if (h->env) api->ReleaseEnv(h->env);
    free(h);
    LOGI("estimator released");
}
