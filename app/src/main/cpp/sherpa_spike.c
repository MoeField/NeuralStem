// sherpa_spike.c — NeuralStem Task 0b Spike JNI 胶水
//
// 把 sherpa-onnx 的 C API(音源分离部分)暴露给 Kotlin:
//   pub.miku.neuralstem.spike.SherpaSourceSeparation
//
// 链接目标:AAR 依赖带入的 libsherpa-onnx-c-api.so(符号已验证存在,
// 见 docs/DEVELOP.md 2026-02 记录),运行时无需再链 onnxruntime。
//
// 数据流(Kotlin → C → Kotlin):
//   Kotlin 交织 float[] → 拆 planar float** → SherpaOnnxOfflineSourceSeparationProcess
//   → 输出 stems(每 stem float**)→ 拼回交织 float[] 打包成 Java 对象返回

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "sherpa-onnx/c-api/c-api.h"

#define TAG "SherpaSpike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------- create ----------

// 引擎类型(Task 0e 后追加,用于 UVR-MDX 对比实验):
//   mode=0 → Spleeter(双模型: vocals + accompaniment)
//   mode=1 → UVR-MDX(单模型)
// 统一用一个入口,由 Kotlin 侧传 mode 区分,避免 JNI 导出膨胀。
JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_spike_SherpaSourceSeparation_nativeCreate(
    JNIEnv *env, jclass clazz, jstring j_vocals, jstring j_accomp,
    jint num_threads, jint mode) {
  if (!j_vocals) {
    LOGE("nativeCreate: j_vocals is null");
    return (jlong)0;
  }
  const char *vocals = (*env)->GetStringUTFChars(env, j_vocals, NULL);
  if (!vocals) {
    LOGE("nativeCreate: GetStringUTFChars(vocals) failed");
    return (jlong)0;
  }
  const char *accomp = NULL;
  if (j_accomp) {
    accomp = (*env)->GetStringUTFChars(env, j_accomp, NULL);
    if (!accomp) {
      LOGE("nativeCreate: GetStringUTFChars(accomp) failed");
      (*env)->ReleaseStringUTFChars(env, j_vocals, vocals);
      return (jlong)0;
    }
  }

  SherpaOnnxOfflineSourceSeparationModelConfig model;
  memset(&model, 0, sizeof(model));
  model.num_threads = num_threads;
  model.debug = 1;  // Spike 阶段打开 onnxruntime 日志,便于真机排障
  model.provider = "cpu";

  if (mode == 1) {
    // UVR-MDX:单模型。vocals 参数承载模型路径,accomp 不用
    SherpaOnnxOfflineSourceSeparationUvrModelConfig uvr;
    memset(&uvr, 0, sizeof(uvr));
    uvr.model = vocals;
    model.uvr = uvr;
    LOGI("creating UVR engine, model=%s threads=%d", vocals, num_threads);
  } else {
    // Spleeter:双模型
    SherpaOnnxOfflineSourceSeparationSpleeterModelConfig spleeter;
    memset(&spleeter, 0, sizeof(spleeter));
    spleeter.vocals = vocals;
    spleeter.accompaniment = accomp;
    model.spleeter = spleeter;
    LOGI("creating Spleeter engine, threads=%d", num_threads);
  }

  SherpaOnnxOfflineSourceSeparationConfig config;
  memset(&config, 0, sizeof(config));
  config.model = model;

  const SherpaOnnxOfflineSourceSeparation *ss =
      SherpaOnnxCreateOfflineSourceSeparation(&config);

  (*env)->ReleaseStringUTFChars(env, j_vocals, vocals);
  if (accomp) (*env)->ReleaseStringUTFChars(env, j_accomp, accomp);

  if (!ss) {
    LOGE("failed to create source separation engine");
    return (jlong)0;
  }
  LOGI("engine created, threads=%d", num_threads);
  // opaque 指针按句柄透传;销毁函数接受 const 指针
  return (jlong)(intptr_t)ss;
}

// ---------- separate ----------

// 把 C stems 结果封装为 Kotlin SeparationResult(vocals/accompaniment 交织)
static jobject MakeResult(JNIEnv *env, const SherpaOnnxSourceSeparationOutput *out) {
  if (!out || out->num_stems < 2 || !out->stems) {
    LOGE("unexpected stems count=%d", out ? out->num_stems : -1);
    return NULL;
  }

  // 找 SeparationResult 构造器 (float[] vocals, float[] accomp, int sampleRate)
  jclass cls = (*env)->FindClass(env,
      "pub/miku/neuralstem/spike/SherpaSourceSeparation$SeparationResult");
  if (!cls) return NULL;
  // 签名 (float[] vocals, float[] accompaniment, int sampleRate)
  // 注意:之前误写成 "([FL[FI)V"(多了一个 L),导致运行时 NoSuchMethodError
  jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>",
      "([F[FI)V");
  if (!ctor) return NULL;

  jfloatArray j_vocals = NULL;
  jfloatArray j_accomp = NULL;

  // stem[0]=vocals, stem[1]=accompaniment(见 sherpa-onnx 文档)
  for (int s = 0; s < 2 && s < out->num_stems; ++s) {
    const SherpaOnnxSourceSeparationStem *stem = &out->stems[s];
    int channels = stem->num_channels;
    int frames = stem->n;
    if (!stem->samples || channels <= 0 || frames <= 0) {
      LOGE("invalid stem[%d]: channels=%d frames=%d samples=%p", s, channels, frames, (void*)stem->samples);
      goto fail;
    }

    jfloatArray j_inter = (*env)->NewFloatArray(env, frames * channels);
    if (!j_inter) {
      LOGE("NewFloatArray failed (stem=%d len=%d)", s, frames * channels);
      goto fail;
    }

    // planar → 交织
    float *inter = (float *)malloc(sizeof(float) * (size_t)frames * (size_t)channels);
    if (!inter) {
      LOGE("malloc inter failed (stem=%d)", s);
      (*env)->DeleteLocalRef(env, j_inter);
      goto fail;
    }
    for (int f = 0; f < frames; ++f) {
      for (int c = 0; c < channels; ++c) {
        inter[f * channels + c] = stem->samples[c][f];
      }
    }
    (*env)->SetFloatArrayRegion(env, j_inter, 0, frames * channels, inter);
    free(inter);
    if ((*env)->ExceptionCheck(env)) {
      LOGE("SetFloatArrayRegion failed (stem=%d)", s);
      (*env)->DeleteLocalRef(env, j_inter);
      goto fail;
    }

    if (s == 0) j_vocals = j_inter; else j_accomp = j_inter;
  }
  if (!j_vocals || !j_accomp) return NULL;

  jobject result = (*env)->NewObject(env, cls, ctor,
      j_vocals, j_accomp, out->sample_rate);
  if (!result) {
    LOGE("NewObject SeparationResult failed");
  }
  return result;

fail:
  if (j_vocals) (*env)->DeleteLocalRef(env, j_vocals);
  if (j_accomp) (*env)->DeleteLocalRef(env, j_accomp);
  return NULL;
}

JNIEXPORT jobject JNICALL
Java_pub_miku_neuralstem_spike_SherpaSourceSeparation_nativeSeparate(
    JNIEnv *env, jobject thiz, jlong handle, jobjectArray j_planar,
    jint channels, jint frames, jint sample_rate) {
  const SherpaOnnxOfflineSourceSeparation *ss =
      (const SherpaOnnxOfflineSourceSeparation *)(intptr_t)handle;
  if (!ss) {
    LOGE("invalid handle");
    return NULL;
  }

  // j_planar: float[][] → 稳定内存的 float* const[](拷贝一份,避免 pin JVM 数组跑长推理)
  if (!j_planar || channels <= 0 || frames <= 0) {
    LOGE("invalid separate args: planar=%p channels=%d frames=%d", (void*)j_planar, channels, frames);
    return NULL;
  }
  if ((*env)->GetArrayLength(env, j_planar) < channels) {
    LOGE("planar length %d < channels %d", (int)(*env)->GetArrayLength(env, j_planar), channels);
    return NULL;
  }
  const float **samples = (const float **)malloc(sizeof(float *) * channels);
  if (!samples) return NULL;
  for (int c = 0; c < channels; ++c) {
    samples[c] = NULL;
    jfloatArray j_ch = (jfloatArray)(*env)->GetObjectArrayElement(env, j_planar, c);
    if (!j_ch) {
      LOGE("planar[%d] is null", c);
      goto input_fail;
    }
    jfloat *ch = (*env)->GetFloatArrayElements(env, j_ch, NULL);
    if (!ch) {
      LOGE("GetFloatArrayElements failed (ch=%d)", c);
      (*env)->DeleteLocalRef(env, j_ch);
      goto input_fail;
    }
    if ((*env)->GetArrayLength(env, j_ch) < frames) {
      LOGE("planar[%d] length %d < frames %d", c, (int)(*env)->GetArrayLength(env, j_ch), frames);
      (*env)->ReleaseFloatArrayElements(env, j_ch, ch, JNI_ABORT);
      (*env)->DeleteLocalRef(env, j_ch);
      goto input_fail;
    }
    float *copy = (float *)malloc(sizeof(float) * (size_t)frames);
    if (!copy) {
      LOGE("malloc copy failed (ch=%d)", c);
      (*env)->ReleaseFloatArrayElements(env, j_ch, ch, JNI_ABORT);
      (*env)->DeleteLocalRef(env, j_ch);
      goto input_fail;
    }
    memcpy(copy, ch, sizeof(float) * (size_t)frames);
    (*env)->ReleaseFloatArrayElements(env, j_ch, ch, JNI_ABORT);
    (*env)->DeleteLocalRef(env, j_ch);
    samples[c] = copy;
  }

  const SherpaOnnxSourceSeparationOutput *out =
      SherpaOnnxOfflineSourceSeparationProcess(ss, samples, channels, frames,
                                               sample_rate);

  for (int c = 0; c < channels; ++c) free((void *)samples[c]);
  free(samples);

  if (!out) {
    LOGE("separation process failed");
    return NULL;
  }
  LOGI("process done: stems=%d sr=%d", out->num_stems, out->sample_rate);

  jobject result = MakeResult(env, out);
  SherpaOnnxDestroySourceSeparationOutput(out);
  return result;

input_fail:
  for (int c = 0; c < channels; ++c) free((void *)samples[c]);
  free(samples);
  return NULL;
}

// ---------- release ----------

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_spike_SherpaSourceSeparation_nativeRelease(
    JNIEnv *env, jobject thiz, jlong handle) {
  const SherpaOnnxOfflineSourceSeparation *ss =
      (const SherpaOnnxOfflineSourceSeparation *)(intptr_t)handle;
  if (ss) {
    SherpaOnnxDestroyOfflineSourceSeparation(ss);
    LOGI("engine released");
  }
}
