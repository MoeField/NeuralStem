// Rubber Band JNI 桥（v0.4.0 spike）。
//
// 只包 rubberband-c.h 的 C API：创建 / 参数 / process / retrieve / latency。
// 输入输出都是 Android 侧的 **interleaved float**，这里转 planar 给 RB。
//
// 线程：与 SonicStage 一致——只允许调用方单线程使用（StemMixer 渲染线程）。
// 许可：本文件 + 编译进来的 RB 源码整体按 GPL-3.0-or-later 分发。

#include <jni.h>
#include <cstdint>
#include <vector>

#include "rubberband-c.h"

namespace {

struct RbHandle {
    RubberBandState state = nullptr;
    int channels = 0;
    // 每声道 planar 缓冲（RB 要求 float* const*）。
    std::vector<std::vector<float>> planarIn;
    std::vector<std::vector<float>> planarOut;
    std::vector<float *> inPtrs;
    std::vector<float *> outPtrs;
};

inline RbHandle *from(jlong h) { return reinterpret_cast<RbHandle *>(h); }

void ensureCapacity(RbHandle *h, int frames) {
    const auto need = static_cast<size_t>(frames);
    for (int c = 0; c < h->channels; ++c) {
        if (h->planarIn[c].size() < need) h->planarIn[c].resize(need);
        if (h->planarOut[c].size() < need) h->planarOut[c].resize(need);
    }
    for (int c = 0; c < h->channels; ++c) {
        h->inPtrs[c] = h->planarIn[c].data();
        h->outPtrs[c] = h->planarOut[c].data();
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_create(
    JNIEnv *, jclass, jint sampleRate, jint channels, jint options,
    jfloat timeRatio, jfloat pitchScale) {
    if (sampleRate <= 0 || channels <= 0) return 0;
    auto *h = new RbHandle();
    h->channels = channels;
    h->planarIn.resize(channels);
    h->planarOut.resize(channels);
    h->inPtrs.resize(channels);
    h->outPtrs.resize(channels);
    ensureCapacity(h, 4096);
    h->state = rubberband_new(
        static_cast<unsigned int>(sampleRate),
        static_cast<unsigned int>(channels),
        static_cast<RubberBandOptions>(options),
        static_cast<double>(timeRatio),
        static_cast<double>(pitchScale));
    if (h->state == nullptr) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_delete(JNIEnv *, jclass, jlong handle) {
    auto *h = from(handle);
    if (h == nullptr) return;
    if (h->state != nullptr) rubberband_delete(h->state);
    delete h;
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_reset(JNIEnv *, jclass, jlong handle) {
    auto *h = from(handle);
    if (h != nullptr && h->state != nullptr) rubberband_reset(h->state);
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_setTimeRatio(
    JNIEnv *, jclass, jlong handle, jfloat ratio) {
    auto *h = from(handle);
    if (h != nullptr && h->state != nullptr) {
        rubberband_set_time_ratio(h->state, static_cast<double>(ratio));
    }
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_setPitchScale(
    JNIEnv *, jclass, jlong handle, jfloat scale) {
    auto *h = from(handle);
    if (h != nullptr && h->state != nullptr) {
        rubberband_set_pitch_scale(h->state, static_cast<double>(scale));
    }
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_process(
    JNIEnv *env, jclass, jlong handle, jfloatArray interleaved, jint frames, jboolean final) {
    auto *h = from(handle);
    if (h == nullptr || h->state == nullptr || frames <= 0) return;
    ensureCapacity(h, frames);

    // interleaved -> planar
    jfloat *in = env->GetFloatArrayElements(interleaved, nullptr);
    if (in == nullptr) return;
    const int ch = h->channels;
    for (int c = 0; c < ch; ++c) {
        float *dst = h->planarIn[c].data();
        const float *src = in + c;
        for (int i = 0; i < frames; ++i) dst[i] = src[i * ch];
    }
    env->ReleaseFloatArrayElements(interleaved, in, JNI_ABORT);

    rubberband_process(h->state, h->inPtrs.data(),
                       static_cast<unsigned int>(frames), final ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_available(JNIEnv *, jclass, jlong handle) {
    auto *h = from(handle);
    if (h == nullptr || h->state == nullptr) return 0;
    return static_cast<jint>(rubberband_available(h->state));
}

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_retrieve(
    JNIEnv *env, jclass, jlong handle, jfloatArray out, jint frames) {
    auto *h = from(handle);
    if (h == nullptr || h->state == nullptr || frames <= 0) return 0;
    const int avail = static_cast<int>(rubberband_available(h->state));
    if (avail <= 0) return 0;
    const int n = frames < avail ? frames : avail;
    ensureCapacity(h, n);

    const int got = static_cast<int>(
        rubberband_retrieve(h->state, h->outPtrs.data(), static_cast<unsigned int>(n)));
    if (got <= 0) return 0;

    // planar -> interleaved
    jfloat *dst = env->GetFloatArrayElements(out, nullptr);
    if (dst == nullptr) return 0;
    const int ch = h->channels;
    for (int c = 0; c < ch; ++c) {
        const float *src = h->planarOut[c].data();
        for (int i = 0; i < got; ++i) dst[i * ch + c] = src[i];
    }
    env->ReleaseFloatArrayElements(out, dst, 0);
    return got;
}

JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_RubberBandNative_latency(JNIEnv *, jclass, jlong handle) {
    auto *h = from(handle);
    if (h == nullptr || h->state == nullptr) return 0;
    return static_cast<jint>(rubberband_get_latency(h->state));
}

}  // extern "C"
