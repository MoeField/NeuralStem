cmake_minimum_required(VERSION 3.22)
# ★ LANGUAGES 加 CXX（2026-10-25）：
#   AVS3 预编译库 libVMFPlatForm.so 依赖 libc++_shared.so。
#   只启 C 语言时，CMake 不会配置 C++ STL → gradle 不会打包 libc++_shared.so
#   → dlopen 报 `library "libc++_shared.so" not found`。
#   加上 CXX 后 AGP 会自动把它放进 APK。
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
# ★AVS3-P3 (av3a) 音频解码 JNI 桥（仅 arm64-v8a）
#
# 依赖两个**预编译** .so（放在 app/src/main/jniLibs/arm64-v8a/）：
#   libAVS3AudioDec.so  324KB  核心解码器
#   libVMFPlatForm.so   185KB  平台层
# 来源：github.com/Dreamello/av3a
#
# 两者不参与链接——本桥用 dlopen 运行时加载，
# 好处：缺库时返回错误码而不是进程启动崩溃。
# ============================================================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(neuralstem_avs3 SHARED avs3_audio_bridge.c)
    target_link_libraries(neuralstem_avs3 log dl)
    target_compile_options(neuralstem_avs3 PRIVATE -O2)
else()
    message(STATUS "AVS3 桥仅支持 arm64-v8a，当前 ${ANDROID_ABI} 跳过")
endif()
