// 阶段 M4 / S2：ONNX Runtime C API 真机探针（自写 JNI）。
//
// 为什么自写 JNI：sherpa AAR 自带 libonnxruntime.so(1.28.2)，而 Maven 的
// onnxruntime-android Java 绑定需要 *完全同版* 的版本化符号（VERS_x.y.z），
// 两者共存会 dlopen 失败。自写 JNI 直接链接 sherpa 那份 .so（符号版本一致）。
//
// 本探针只验证一件事：**ORT 能否在本机加载并跑通 Demucs ONNX**。
// 不做 STFT（那部分已在桌面用 Python 验证，见 doc/.memory.md §M4）。
#include <jni.h>
#include <android/log.h>
#include <time.h>
#include <stdlib.h>
#include <stdio.h>
#include "onnxruntime_c_api.h"

#define TAG "OrtProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

JNIEXPORT jstring JNICALL
Java_pub_miku_neuralstem_spike_OrtProbe_nativeProbeReal(
    JNIEnv *env, jobject thiz, jstring jmodelPath, jfloatArray jwave, jfloatArray jspec) {
    const char *modelPath = (*env)->GetStringUTFChars(env, jmodelPath, NULL);
    char summary[768];
    snprintf(summary, sizeof(summary), "FAIL: unknown");

    const OrtApiBase *base = OrtGetApiBase();
    if (!base) {
        LOGE("OrtGetApiBase() == NULL");
        snprintf(summary, sizeof(summary), "FAIL: OrtGetApiBase null");
        goto out_str;
    }
    LOGI("ORT version string = %s", base->GetVersionString ? base->GetVersionString() : "?");
    const OrtApi *api = base->GetApi(ORT_API_VERSION);
    if (!api) {
        LOGE("GetApi(%d) returned NULL", ORT_API_VERSION);
        snprintf(summary, sizeof(summary), "FAIL: GetApi(%d) null (版本不匹配?)", ORT_API_VERSION);
        goto out_str;
    }

    OrtEnv *ortEnv = NULL;
    OrtSessionOptions *opts = NULL;
    OrtSession *session = NULL;
    OrtMemoryInfo *mem = NULL;
    OrtValue *inWave = NULL, *inSpec = NULL, *outSpec = NULL, *outTime = NULL;
    const OrtValue *inputs[2];
    const OrtValue *outputs[2] = {NULL, NULL};   // ★ 必须先置空，否则 cleanup 时 ReleaseValue 未初始化指针
    const char *inNames[2] = {"input", "x"};
    const char *outNames[2] = {"output", "add_67"};
    OrtStatus *st = NULL;
    float *wave = NULL, *spec = NULL;
    LOGI("PROBE step1: before CreateEnv");
    const int64_t shapeWave[3] = {1, 2, 343980};
    const int64_t shapeSpec[4] = {1, 4, 2048, 336};

    long t0 = now_ms();
    st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "ortprobe", &ortEnv);
    if (st) { LOGE("CreateEnv: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    st = api->CreateSessionOptions(&opts);
    if (st) { LOGE("CreateSessionOptions: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    api->SetIntraOpNumThreads(opts, 4);

    st = api->CreateSession(ortEnv, modelPath, opts, &session);
    if (st) { LOGE("CreateSession: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    long loadMs = now_ms() - t0;
    LOGI("session created in %ldms", loadMs);

    st = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
    if (st) { LOGE("CreateCpuMemoryInfo: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }

    // 输入：由 Kotlin 侧传入**真实数据**（真实波形 + 其 STFT）
    //   —— 上一版用合成垃圾数据，会触发 NaN→非法索引→SIGBUS，无法判定 ORT 是否可用
    const size_t nWave = 2 * 343980, nSpec = 4 * 2048 * 336;
    if ((*env)->GetArrayLength(env, jwave) != (jsize)nWave) {
        snprintf(summary, sizeof(summary), "FAIL: wave len %d != %zu",
                 (int)(*env)->GetArrayLength(env, jwave), nWave);
        goto out_str;
    }
    if ((*env)->GetArrayLength(env, jspec) != (jsize)nSpec) {
        snprintf(summary, sizeof(summary), "FAIL: spec len %d != %zu",
                 (int)(*env)->GetArrayLength(env, jspec), nSpec);
        goto out_str;
    }
    wave = (float *)malloc(nWave * sizeof(float));
    spec = (float *)malloc(nSpec * sizeof(float));
    if (!wave || !spec) { snprintf(summary, sizeof(summary), "FAIL: malloc"); goto cleanup; }
    (*env)->GetFloatArrayRegion(env, jwave, 0, (jsize)nWave, wave);
    (*env)->GetFloatArrayRegion(env, jspec, 0, (jsize)nSpec, spec);

    st = api->CreateTensorWithDataAsOrtValue(mem, wave, nWave * sizeof(float), shapeWave, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inWave);
    if (st) { LOGE("CreateTensor wave: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    st = api->CreateTensorWithDataAsOrtValue(mem, spec, nSpec * sizeof(float), shapeSpec, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inSpec);
    if (st) { LOGE("CreateTensor spec: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }

    // ★ 显式判空：若创建失败却未判空，Run 会解引用 NULL（实测 SEGV @0x20）
    if (!inWave || !inSpec) {
        LOGE("tensor create returned null: inWave=%p inSpec=%p", (void *)inWave, (void *)inSpec);
        snprintf(summary, sizeof(summary), "FAIL: tensor null wave=%p spec=%p", (void *)inWave, (void *)inSpec);
        goto cleanup;
    }
    if (!mem) {
        snprintf(summary, sizeof(summary), "FAIL: meminfo null");
        goto cleanup;
    }

    // 顺便确认 session 的输入/输出名（若名字不匹配，Run 会返回错误而非崩溃）
    {
        OrtAllocator *alloc = NULL;
        api->GetAllocatorWithDefaultOptions(&alloc);
        if (alloc) {
            size_t nIn = 0, nOut = 0;
            api->SessionGetInputCount(session, &nIn);
            api->SessionGetOutputCount(session, &nOut);
            for (size_t i = 0; i < nIn; i++) {
                char *nm = NULL;
                if (api->SessionGetInputName(session, i, alloc, &nm) == NULL && nm) {
                    LOGI("session input[%zu] = %s", i, nm);
                }
            }
            for (size_t i = 0; i < nOut; i++) {
                char *nm = NULL;
                if (api->SessionGetOutputName(session, i, alloc, &nm) == NULL && nm) {
                    LOGI("session output[%zu] = %s", i, nm);
                }
            }
        }
    }

    inputs[0] = inWave; inputs[1] = inSpec;
    LOGI("PROBE step6: about to Run");
    long ti = now_ms();
    st = api->Run(session, NULL, inNames, inputs, 2, outNames, 2, (OrtValue **)outputs);
    long inferMs = now_ms() - ti;
    if (st) { LOGE("Run: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    LOGI("PROBE step7: Run returned OK");

    // 读输出形状
    OrtTensorTypeAndShapeInfo *ti2 = NULL;
    size_t dims = 0; int64_t d[8] = {0};
    api->GetTensorTypeAndShape(outputs[0], &ti2);
    api->GetDimensionsCount(ti2, &dims);
    if (dims > 8) dims = 8;
    api->GetDimensions(ti2, d, dims);
    api->ReleaseTensorTypeAndShapeInfo(ti2);
    LOGI("output[0] dims=%zu [%lld,%lld,%lld,%lld,%lld]", dims,
         (long long)d[0], (long long)d[1], (long long)d[2], (long long)d[3], (long long)d[4]);

    size_t dims2 = 0; int64_t e[8] = {0};
    api->GetTensorTypeAndShape(outputs[1], &ti2);
    api->GetDimensionsCount(ti2, &dims2);
    if (dims2 > 8) dims2 = 8;
    api->GetDimensions(ti2, e, dims2);
    api->ReleaseTensorTypeAndShapeInfo(ti2);
    LOGI("output[1] dims=%zu [%lld,%lld,%lld]", dims2,
         (long long)e[0], (long long)e[1], (long long)e[2]);

    snprintf(summary, sizeof(summary),
             "ORT_OK ortVersion=%s loadMs=%ld inferMs=%ld out0=[%lld,%lld,%lld,%lld,%lld] out1=[%lld,%lld,%lld]",
             base->GetVersionString ? base->GetVersionString() : "?",
             loadMs, inferMs,
             (long long)d[0], (long long)d[1], (long long)d[2], (long long)d[3], (long long)d[4],
             (long long)e[0], (long long)e[1], (long long)e[2]);

cleanup:
    if (inWave) api->ReleaseValue(inWave);
    if (inSpec) api->ReleaseValue(inSpec);
    if (outputs[0]) api->ReleaseValue((OrtValue *)outputs[0]);
    if (outputs[1]) api->ReleaseValue((OrtValue *)outputs[1]);
    if (mem) api->ReleaseMemoryInfo(mem);
    if (session) api->ReleaseSession(session);
    if (opts) api->ReleaseSessionOptions(opts);
    if (ortEnv) api->ReleaseEnv(ortEnv);
    free(wave); free(spec);

out_str:
    (*env)->ReleaseStringUTFChars(env, jmodelPath, modelPath);
    return (*env)->NewStringUTF(env, summary);
}
