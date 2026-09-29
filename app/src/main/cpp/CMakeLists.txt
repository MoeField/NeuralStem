cmake_minimum_required(VERSION 3.22)
# C++ 用于 SoundTouch（R2/bug C：替换 Sonic）；C 用于既有 JNI 胶水
project(neuralstem_spike LANGUAGES C CXX)

set(SHERPA_LIB_DIR ${CMAKE_SOURCE_DIR}/sherpa-libs/${ANDROID_ABI})

# sherpa-onnx c-api 预编译库(来自官方 AAR 解包,版本 1.13.8)
add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES IMPORTED_LOCATION
    ${SHERPA_LIB_DIR}/libsherpa-onnx-c-api.so)

add_library(neuralstem_spike SHARED sherpa_spike.c)

target_include_directories(neuralstem_spike PRIVATE
    ${CMAKE_SOURCE_DIR}/sherpa-libs/include)

target_link_libraries(neuralstem_spike
    sherpa-onnx-c-api
    log)

target_compile_options(neuralstem_spike PRIVATE -O2)

# libsherpa-onnx-c-api.so 自身链接了 libonnxruntime(AAR 会一并打包),
# 但为保险起见运行时允许未解析符号由 dlopen 顺序补齐
set_target_properties(neuralstem_spike PROPERTIES LINK_FLAGS "-Wl,--allow-shlib-undefined")

# ============================================================================
# M4/S2：ONNX Runtime 探针（自写 JNI）
# 链接的是 **sherpa AAR 自带的那份 libonnxruntime.so**（1.28.2）——
# 这样符号版本与 sherpa 一致，避开 Maven Java 绑定(1.22.0)的 dlopen 冲突。
# 运行时需先 System.loadLibrary("onnxruntime") 再加载本库。
set(ORT_LIB_DIR ${CMAKE_SOURCE_DIR}/ort-libs/${ANDROID_ABI})

if(EXISTS ${ORT_LIB_DIR}/libonnxruntime.so)
    add_library(onnxruntime SHARED IMPORTED)
    set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION
        ${ORT_LIB_DIR}/libonnxruntime.so)

    add_library(neuralstem_ort SHARED ort_probe.c spleeter4_probe.c spleeter4_estimator.c demucs_estimator.c)
    target_include_directories(neuralstem_ort PRIVATE
        ${CMAKE_SOURCE_DIR}/ort-libs/include)
    target_link_libraries(neuralstem_ort onnxruntime log)
    target_compile_options(neuralstem_ort PRIVATE -O2)
else()
    message(WARNING "ort-libs/${ANDROID_ABI}/libonnxruntime.so 不存在，跳过 neuralstem_ort 目标")
endif()

# ============================================================================
# R2 / bug C：SoundTouch（内置源码，LGPL-2.1，见 soundtouch/README.vendor.md）
# 目的：替换 media3 内置 Sonic，避开“频繁变速变调→Sonic 预填充饥饿”导致的
# StuckPlayerDetector→ERROR_CODE_TIMEOUT。（依据见 research_timescale.artifact.md）
# 文件清单参照官方 source/Android-lib/jni/Android.mk。
set(SOUNDTOUCH_DIR ${CMAKE_SOURCE_DIR}/soundtouch)

add_library(soundtouch STATIC
    ${SOUNDTOUCH_DIR}/source/AAFilter.cpp
    ${SOUNDTOUCH_DIR}/source/FIFOSampleBuffer.cpp
    ${SOUNDTOUCH_DIR}/source/FIRFilter.cpp
    ${SOUNDTOUCH_DIR}/source/cpu_detect_x86.cpp
    ${SOUNDTOUCH_DIR}/source/sse_optimized.cpp
    ${SOUNDTOUCH_DIR}/source/mmx_optimized.cpp
    ${SOUNDTOUCH_DIR}/source/RateTransposer.cpp
    ${SOUNDTOUCH_DIR}/source/SoundTouch.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateCubic.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateLinear.cpp
    ${SOUNDTOUCH_DIR}/source/InterpolateShannon.cpp
    ${SOUNDTOUCH_DIR}/source/TDStretch.cpp
    # 注：不包含 BPMDetect.cpp / PeakFinder.cpp —— 那是 BPM 检测，本项目不需要
)
target_include_directories(soundtouch PUBLIC
    ${SOUNDTOUCH_DIR}/include
    ${SOUNDTOUCH_DIR}/source)
# 官方 Android.mk 用 -fvisibility=hidden（仅导出 JNI 符号）；-fexceptions 对应 Application.mk
# ★ 16-bit 整数采样：media3 对 ENCODING_PCM_16BIT 永远 FORMAT_SUPPORTED_DIRECTLY；
#   float 路径已实测不可行（BF00A 与骁龙 8 Gen3 的 floatSupport 均为 2，见研究文档）。
#   用 PUBLIC 定义，让链接方（neuralstem_soundtouch）也继承同一宏，否则 SAMPLETYPE 不一致。
target_compile_options(soundtouch PRIVATE -O2 -fexceptions -fvisibility=hidden)
target_compile_definitions(soundtouch PUBLIC SOUNDTOUCH_INTEGER_SAMPLES=1)

# JNI 包装（后续步骤填充 soundtouch_jni.cpp）
if(EXISTS ${CMAKE_SOURCE_DIR}/soundtouch_jni.cpp)
    add_library(neuralstem_soundtouch SHARED soundtouch_jni.cpp)
    target_include_directories(neuralstem_soundtouch PRIVATE
        ${SOUNDTOUCH_DIR}/include)
    target_link_libraries(neuralstem_soundtouch soundtouch log)
    target_compile_options(neuralstem_soundtouch PRIVATE -O2 -fexceptions)
else()
    message(STATUS "soundtouch_jni.cpp 不存在，暂不构建 neuralstem_soundtouch")
endif()
