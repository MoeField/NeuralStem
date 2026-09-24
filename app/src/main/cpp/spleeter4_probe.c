// 阶段 M4 / S3：Spleeter 4-stem ONNX 真机探针（自写 JNI，复用 S2 的 ORT C 链接方式）。
//
// 契约（一手核对自 ONNX 元数据 + Best-Practice/spleeter-4stems-onnx README）：
//   IN  x : [2, num_splits, 512, 1024]   幅度谱（1024 of 2049 bins）
//   OUT y : [2, num_splits, 512, 1024]   该 stem 的幅度估计（ELU 激活）
// 4 个 stem 各自独立模型：vocals / drums / bass / other。
//
// 本探针只验证「4 个模型能否在本机 ORT 依次跑通 + 耗时/内存」。
// 完整的 mask/iSTFT 合并已在桌面用 Python 验证（重建不变量 -163.5 dB，见 .memory.md §M4-S3）。
#include <jni.h>
#include <android/log.h>
#include <time.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "onnxruntime_c_api.h"

#define TAG "Spleeter4"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static long now_ms4(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

// 固定 1 split（8s 音频 = 348 帧 < 512）
#define SPLITS 1
#define FRAMES 512
#define BINS   1024
#define NCH    2
#define IN_LEN (NCH * SPLITS * FRAMES * BINS)   // 2*1*512*1024 = 1,048,576

// 依次跑 4 个模型：每次建会话 → 喂同一份幅度谱 → 读输出形状。
// 返回摘要字符串。
JNIEXPORT jstring JNICALL
Java_pub_miku_neuralstem_spike_Spleeter4Probe_nativeProbe4(
    JNIEnv *env, jobject thiz, jstring j_dir, jfloatArray j_mag) {
    const char *dir = (*env)->GetStringUTFChars(env, j_dir, NULL);
    char summary[768];
    snprintf(summary, sizeof(summary), "FAIL: unknown");

    const OrtApiBase *base = OrtGetApiBase();
    const OrtApi *api = base ? base->GetApi(ORT_API_VERSION) : NULL;
    if (!api) {
        snprintf(summary, sizeof(summary), "FAIL: GetApi(%d) null", ORT_API_VERSION);
        goto out_str;
    }

    if ((*env)->GetArrayLength(env, j_mag) != IN_LEN) {
        snprintf(summary, sizeof(summary), "FAIL: mag len %d != %d",
                 (int)(*env)->GetArrayLength(env, j_mag), IN_LEN);
        goto out_str;
    }

    static const char *STEMS[4] = {"vocals", "drums", "bass", "other"};
    const int64_t shapeIn[4] = {NCH, SPLITS, FRAMES, BINS};
    const char *inNames[1] = {"x"};
    const char *outNames[1] = {"y"};

    float *mag = (float *)malloc(IN_LEN * sizeof(float));
    if (!mag) { snprintf(summary, sizeof(summary), "FAIL: malloc"); goto out_str; }
    (*env)->GetFloatArrayRegion(env, j_mag, 0, IN_LEN, mag);

    OrtEnv *ortEnv = NULL;
    OrtSessionOptions *opts = NULL;
    OrtMemoryInfo *mem = NULL;
    OrtStatus *st = NULL;
    long tEnv = now_ms4();
    st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "sp4", &ortEnv);
    if (st) { LOGE("CreateEnv: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    st = api->CreateSessionOptions(&opts);
    if (st) { LOGE("CreateSessionOptions: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    api->SetIntraOpNumThreads(opts, 4);
    st = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
    if (st) { LOGE("CreateCpuMemoryInfo: %s", api->GetErrorMessage(st)); api->ReleaseStatus(st); goto cleanup; }
    (void)tEnv;

    long totalLoad = 0, totalInfer = 0;
    int okCount = 0;
    char shapeBuf[512]; shapeBuf[0] = 0;

    for (int s = 0; s < 4; s++) {
        char path[512];
        snprintf(path, sizeof(path), "%s/%s.fp16.onnx", dir, STEMS[s]);

        OrtSession *session = NULL;
        OrtValue *inVal = NULL, *outVal = NULL;
        const OrtValue *outputs[1] = {NULL};

        long tl = now_ms4();
        st = api->CreateSession(ortEnv, path, opts, &session);
        totalLoad += now_ms4() - tl;
        if (st || !session) {
            LOGE("CreateSession(%s) failed: %s", STEMS[s], st ? api->GetErrorMessage(st) : "null");
            if (st) api->ReleaseStatus(st);
            if (session) api->ReleaseSession(session);
            continue;
        }

        st = api->CreateTensorWithDataAsOrtValue(mem, mag, IN_LEN * sizeof(float),
                                                 shapeIn, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inVal);
        if (st || !inVal) {
            LOGE("CreateTensor(%s) failed: %s", STEMS[s], st ? api->GetErrorMessage(st) : "null");
            if (st) api->ReleaseStatus(st);
            api->ReleaseSession(session);
            continue;
        }

        long ti = now_ms4();
        st = api->Run(session, NULL, inNames, (const OrtValue *const *)&inVal, 1,
                      outNames, 1, (OrtValue **)outputs);
        totalInfer += now_ms4() - ti;
        if (st || !outputs[0]) {
            LOGE("Run(%s) failed: %s", STEMS[s], st ? api->GetErrorMessage(st) : "null out");
            if (st) api->ReleaseStatus(st);
            api->ReleaseValue(inVal);
            api->ReleaseSession(session);
            continue;
        }

        OrtTensorTypeAndShapeInfo *ti2 = NULL;
        size_t dims = 0; int64_t d[8] = {0};
        api->GetTensorTypeAndShape(outputs[0], &ti2);
        api->GetDimensionsCount(ti2, &dims);
        if (dims > 8) dims = 8;
        api->GetDimensions(ti2, d, dims);
        api->ReleaseTensorTypeAndShapeInfo(ti2);
        LOGI("%s: out dims=%zu [%lld,%lld,%lld,%lld]",
             STEMS[s], dims, (long long)d[0], (long long)d[1], (long long)d[2], (long long)d[3]);

        char one[128];
        snprintf(one, sizeof(one), " %s=[%lld,%lld,%lld,%lld]",
                 STEMS[s], (long long)d[0], (long long)d[1], (long long)d[2], (long long)d[3]);
        strncat(shapeBuf, one, sizeof(shapeBuf) - strlen(shapeBuf) - 1);

        okCount++;
        api->ReleaseValue(outputs[0]);
        api->ReleaseValue(inVal);
        api->ReleaseSession(session);
    }

    snprintf(summary, sizeof(summary),
             "SP4_OK ok=%d/4 ortVersion=%s loadMs=%ld inferMs=%ld inLen=%d out:%s",
             okCount, base->GetVersionString ? base->GetVersionString() : "?",
             totalLoad, totalInfer, IN_LEN, shapeBuf);
    LOGI("%s", summary);

cleanup:
    if (mem) api->ReleaseMemoryInfo(mem);
    if (opts) api->ReleaseSessionOptions(opts);
    if (ortEnv) api->ReleaseEnv(ortEnv);
    free(mag);

out_str:
    (*env)->ReleaseStringUTFChars(env, j_dir, dir);
    return (*env)->NewStringUTF(env, summary);
}
