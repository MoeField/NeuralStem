// 阶段 M4 / S2 落地：Demucs（htdemucs）**估计器**（句柄式 JNI，持有 ORT 会话）。
//
// 契约（已在桌面用 onnxruntime 验证；见 .memory.md §M4-S2）：
//   IN  input : [1, 2, 343980]        波形（固定 7.8s 窗）
//   IN  x     : [1, 4, 2048, 336]     STFT(波形)（Hann 4096/1024/center，2048 bins）
//   OUT output: [1, 4, 4, 2048, 336]  频谱分支（4 源 × 复数）
//   OUT add_67: [1, 4, 2, 343980]     时域分支
// 最终（Kotlin 侧）：stem = iSTFT(output) + add_67
//
// ★ 教训（坑 48）：所有 ORT 句柄必须初始化；Run 前显式判空。
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "onnxruntime_c_api.h"

#define TAG "DemucsEst"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// NNAPI 执行提供者（sherpa 的 ORT 导出了该符号，但头文件未声明）—— 用 extern 引用。
// 在支持 NNAPI 的设备（如骁龙 8 Gen3）上可能加速模型推理（占 Demucs 总时间 ~70%）。
extern OrtStatus *OrtSessionOptionsAppendExecutionProvider_Nnapi(OrtSessionOptions *options,
                                                               uint32_t nnapi_flags);

#define WAVE_LEN 343980
#define SPEC_LEN (4 * 2048 * 336)      // 2,752,512
#define OUT_SPEC_LEN (4 * 4 * 2048 * 336)  // 11,010,048
#define OUT_WAVE_LEN (4 * 2 * WAVE_LEN)    // 2,751,840

typedef struct {
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *session;
    OrtSessionOptions *opts;
    OrtMemoryInfo *mem;
} DemucsHandle;

JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_spike_DemucsEstimator_nativeCreate(
    JNIEnv *env, jclass clazz, jstring j_model, jint threads, jboolean use_nnapi) {
    const char *model = (*env)->GetStringUTFChars(env, j_model, NULL);
    const OrtApiBase *base = OrtGetApiBase();
    const OrtApi *api = base ? base->GetApi(ORT_API_VERSION) : NULL;
    if (!api) {
        LOGE("GetApi null");
        (*env)->ReleaseStringUTFChars(env, j_model, model);
        return 0;
    }
    DemucsHandle *h = (DemucsHandle *)calloc(1, sizeof(DemucsHandle));
    if (!h) { (*env)->ReleaseStringUTFChars(env, j_model, model); return 0; }
    h->api = api;

    OrtStatus *st = NULL;
    st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "demucs", &h->env);
    if (st) { LOGE("CreateEnv: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }
    st = api->CreateSessionOptions(&h->opts);
    if (st) { LOGE("CreateSessionOptions: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }
    // ★ 性能：ORT 图优化（ALL 常为默认，但显式设置更稳）
    api->SetSessionGraphOptimizationLevel(h->opts, ORT_ENABLE_ALL);
    api->SetIntraOpNumThreads(h->opts, threads > 0 ? threads : 4);
    if (use_nnapi) {
        // 尝试 NNAPI（骁龙 NPU）；失败不致命（回退 CPU）
        OrtStatus *ns = OrtSessionOptionsAppendExecutionProvider_Nnapi(h->opts, 0);
        if (ns) {
            LOGE("NNAPI EP failed: %s (回退 CPU)", api->GetErrorMessage(ns));
            api->ReleaseStatus(ns);
        } else {
            LOGI("NNAPI EP appended");
        }
    }
    // ★ 内存：曾尝试 session.use_env_allocators=0 / arena_extend_strategy=kSameAsRequested，
    //   实测对峰值 PSS **无影响**（~5GB，与块数无关）—— 这是 htdemucs 大激活张量在
    //   ORT CPU arena 下的固有行为，无会话级旋钮可调。故不再设置（避免误导）。
    st = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &h->mem);
    if (st) { LOGE("CreateCpuMemoryInfo: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto fail; }

    st = api->CreateSession(h->env, model, h->opts, &h->session);
    if (st || !h->session) {
        LOGE("CreateSession failed: %s", st ? api->GetErrorMessage(st) : "null");
        if (st) api->ReleaseStatus(st);
        goto fail;
    }
    (*env)->ReleaseStringUTFChars(env, j_model, model);
    LOGI("demucs estimator created, threads=%d", threads);
    return (jlong)(intptr_t)h;

fail:
    (*env)->ReleaseStringUTFChars(env, j_model, model);
    if (h) {
        if (h->session) api->ReleaseSession(h->session);
        if (h->mem) api->ReleaseMemoryInfo(h->mem);
        if (h->opts) api->ReleaseSessionOptions(h->opts);
        if (h->env) api->ReleaseEnv(h->env);
        free(h);
    }
    return 0;
}

// 返回 Object[2]：{ float[OUT_SPEC_LEN], float[OUT_WAVE_LEN] }
JNIEXPORT jobjectArray JNICALL
Java_pub_miku_neuralstem_spike_DemucsEstimator_nativeEstimate(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray j_wave, jfloatArray j_spec) {
    DemucsHandle *h = (DemucsHandle *)(intptr_t)handle;
    if (!h || !h->session) { LOGE("invalid handle"); return NULL; }
    const OrtApi *api = h->api;

    if ((*env)->GetArrayLength(env, j_wave) != 2 * WAVE_LEN ||
        (*env)->GetArrayLength(env, j_spec) != SPEC_LEN) {
        LOGE("input len mismatch: wave=%d spec=%d",
             (int)(*env)->GetArrayLength(env, j_wave), (int)(*env)->GetArrayLength(env, j_spec));
        return NULL;
    }

    float *wave = (float *)malloc(sizeof(float) * 2 * WAVE_LEN);
    float *spec = (float *)malloc(sizeof(float) * SPEC_LEN);
    if (!wave || !spec) { LOGE("malloc failed"); free(wave); free(spec); return NULL; }
    (*env)->GetFloatArrayRegion(env, j_wave, 0, 2 * WAVE_LEN, wave);
    (*env)->GetFloatArrayRegion(env, j_spec, 0, SPEC_LEN, spec);

    const int64_t shapeWave[3] = {1, 2, WAVE_LEN};
    const int64_t shapeSpec[4] = {1, 4, 2048, 336};
    const char *inNames[2] = {"input", "x"};
    const char *outNames[2] = {"output", "add_67"};
    const OrtValue *outputs[2] = {NULL, NULL};
    OrtValue *inWave = NULL, *inSpec = NULL;

    OrtStatus *st = api->CreateTensorWithDataAsOrtValue(
        h->mem, wave, (size_t)2 * WAVE_LEN * sizeof(float), shapeWave, 3,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inWave);
    if (st || !inWave) { LOGE("CreateTensor wave: %s", st ? api->GetErrorMessage(st) : "null"); if (st) api->ReleaseStatus(st); goto cleanup; }
    st = api->CreateTensorWithDataAsOrtValue(
        h->mem, spec, (size_t)SPEC_LEN * sizeof(float), shapeSpec, 4,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inSpec);
    if (st || !inSpec) { LOGE("CreateTensor spec: %s", st ? api->GetErrorMessage(st) : "null"); if (st) api->ReleaseStatus(st); goto cleanup; }

    const OrtValue *inputs[2] = {inWave, inSpec};
    st = api->Run(h->session, NULL, inNames, inputs, 2, outNames, 2, (OrtValue **)outputs);
    if (st || !outputs[0] || !outputs[1]) {
        LOGE("Run: %s", st ? api->GetErrorMessage(st) : "null out");
        if (st) api->ReleaseStatus(st);
        goto cleanup;
    }

    // 拷回 Java
    jclass floatArrCls = (*env)->FindClass(env, "[F");
    jobjectArray result = (*env)->NewObjectArray(env, 2, floatArrCls, NULL);
    {
        float *p = NULL;
        api->GetTensorMutableData(outputs[0], (void **)&p);
        jfloatArray a = (*env)->NewFloatArray(env, OUT_SPEC_LEN);
        (*env)->SetFloatArrayRegion(env, a, 0, OUT_SPEC_LEN, p);
        (*env)->SetObjectArrayElement(env, result, 0, a);

        api->GetTensorMutableData(outputs[1], (void **)&p);
        jfloatArray b = (*env)->NewFloatArray(env, OUT_WAVE_LEN);
        (*env)->SetFloatArrayRegion(env, b, 0, OUT_WAVE_LEN, p);
        (*env)->SetObjectArrayElement(env, result, 1, b);
    }

    free(wave); free(spec);
    if (outputs[0]) api->ReleaseValue((OrtValue *)outputs[0]);
    if (outputs[1]) api->ReleaseValue((OrtValue *)outputs[1]);
    if (inWave) api->ReleaseValue(inWave);
    if (inSpec) api->ReleaseValue(inSpec);
    return result;

cleanup:
    free(wave); free(spec);
    if (outputs[0]) api->ReleaseValue((OrtValue *)outputs[0]);
    if (outputs[1]) api->ReleaseValue((OrtValue *)outputs[1]);
    if (inWave) api->ReleaseValue(inWave);
    if (inSpec) api->ReleaseValue(inSpec);
    return NULL;
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_spike_DemucsEstimator_nativeRelease(
    JNIEnv *env, jclass clazz, jlong handle) {
    DemucsHandle *h = (DemucsHandle *)(intptr_t)handle;
    if (!h) return;
    const OrtApi *api = h->api;
    if (h->session) api->ReleaseSession(h->session);
    if (h->mem) api->ReleaseMemoryInfo(h->mem);
    if (h->opts) api->ReleaseSessionOptions(h->opts);
    if (h->env) api->ReleaseEnv(h->env);
    free(h);
    LOGI("demucs estimator released");
}
