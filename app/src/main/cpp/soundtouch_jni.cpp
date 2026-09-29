////////////////////////////////////////////////////////////////////////////////
// SoundTouch JNI 包装（NeuralStem，R2/bug C）
//
// 目的：把 SoundTouch 的流式 API 暴露给 Kotlin，供 media3 AudioProcessor 使用，
//       替换内置 Sonic（避开“频繁变速变调→Sonic 预填充饥饿→ERROR_CODE_TIMEOUT”）。
//
// 与官方 source/Android-lib/jni/soundtouch-jni.cpp 的区别：
//   官方是**文件式**（WavFile 读→处理→写）；我们需要**流式**（push PCM → pull PCM）。
//
// ★ 采样类型：**16-bit 整数**（CMake 定义 SOUNDTOUCH_INTEGER_SAMPLES）。
//   理由：media3 对 ENCODING_PCM_16BIT 永远 FORMAT_SUPPORTED_DIRECTLY；
//   而 float 路径实测不可行（BF00A 与骁龙 8 Gen3 的 floatSupport 均=2，见研究文档）。
//   代价：内部 16-bit 精度（与内置 SonicAudioProcessor 同精度）。
//
// 线程约束：单实例只在 audio 线程使用（与 AudioProcessor 契约一致），不加锁。
////////////////////////////////////////////////////////////////////////////////

#include <jni.h>
#include <android/log.h>
#include <stdexcept>

#include "SoundTouch.h"

#define LOG_TAG "SoundTouchJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace soundtouch;

// 采样类型断言：本工程按 16-bit 整数（SITypes 定义 SOUNDTOUCH_INTEGER_SAMPLES）
static_assert(sizeof(SAMPLETYPE) == 2, "expect 16-bit int SAMPLETYPE");

extern "C" {

/** 创建实例。返回原生指针（0 表示失败）。 */
JNIEXPORT jlong JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeCreate(
    JNIEnv *env, jobject /*thiz*/, jint sampleRate, jint channels) {
  try {
    auto *st = new SoundTouch();
    st->setSampleRate(sampleRate);
    st->setChannels(channels);
    return reinterpret_cast<jlong>(st);
  } catch (const std::exception &e) {
    LOGE("nativeCreate failed: %s", e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeClose(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  delete st;
}

/** 设置变速（运行期改变，不重建对象——SoundTouch 的关键优势）。 */
JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeSetTempo(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jdouble tempo) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  if (st) st->setTempo(tempo);
}

/** 设置变调（运行期改变）。 */
JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeSetPitch(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jdouble pitch) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  if (st) st->setPitch(pitch);
}

/**
 * 投入 PCM 样本（交错，16-bit int）。
 * @param offset 起始（以 sample 计）
 * @param count  样本数（frame × channels）
 */
JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativePutSamples(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray samples, jint offset, jint count) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  if (!st || count <= 0) return;
  jshort *buf = env->GetShortArrayElements(samples, nullptr);
  if (buf == nullptr) return;
  try {
    st->putSamples(reinterpret_cast<const SAMPLETYPE *>(buf + offset),
                   static_cast<uint>(count));
  } catch (const std::exception &e) {
    LOGE("putSamples failed: %s", e.what());
  }
  env->ReleaseShortArrayElements(samples, buf, JNI_ABORT);  // 只读，不回写
}

/**
 * 取出已处理样本（交错，16-bit int）。
 * @return 实际取出的样本数（0 = 暂无输出）
 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeReceiveSamples(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray out, jint offset, jint maxCount) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  if (!st || maxCount <= 0) return 0;
  jshort *buf = env->GetShortArrayElements(out, nullptr);
  if (buf == nullptr) return 0;
  uint got = 0;
  try {
    got = st->receiveSamples(reinterpret_cast<SAMPLETYPE *>(buf + offset),
                             static_cast<uint>(maxCount));
  } catch (const std::exception &e) {
    LOGE("receiveSamples failed: %s", e.what());
  }
  env->ReleaseShortArrayElements(out, buf, 0);  // 回写结果
  return static_cast<jint>(got);
}

/** EOS：冲刷流水线尾部残留。 */
JNIEXPORT void JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeFlush(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  if (st) st->flush();
}

/** 当前可立即取出的“就绪”样本数（输出队列）。 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeNumSamples(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  return st ? static_cast<jint>(st->numSamples()) : 0;
}

/** 当前建议的输入块样本数（反映处理延迟）。 */
JNIEXPORT jint JNICALL
Java_pub_miku_neuralstem_player_SoundTouchNative_nativeGetInputSampleReq(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
  auto *st = reinterpret_cast<SoundTouch *>(handle);
  return st ? static_cast<jint>(st->getSetting(SETTING_NOMINAL_INPUT_SEQUENCE)) : 0;
}

}  // extern "C"
