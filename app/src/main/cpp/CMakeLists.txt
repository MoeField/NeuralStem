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
    # ★NEURALSTEM_AVS3_BUILTIN 在文件末尾定义（必须先定义 avs3dec 目标）
else()
    message(STATUS "AVS3 桥仅支持 arm64-v8a，当前 ${ANDROID_ABI} 跳过")
endif()

# ============================================================================
# ★AVS3-P3 解码器「自编译版」（2026-10-26）
#
# ## 为什么要自己编
# 厂商预编译的 libAVS3AudioDec.so 在 AArch64 上有代码生成 bug：
# Avs3InitDecoder 内 0x3144 处 ldrsH x21,[x25,#0x1202] 必崩（详见
# doc/.memory.md §8.5.3）。我们没有它的源码工程配置，但它其实是这个仓库
# Dreamello/av3a 的 av3adecoder/ 编出来的 —— 源码就在 avs3dec/ 下。
# 用自己的工具链重编一遍，同样的逻辑就会生成正确的寻址指令。
#
# ## 源码布局（从 Dreamello/av3a 原样拷入）
#   avs3dec/src/     avs3Decoder/src/*.c        13 个（解码器主体 + decoder.c 导出层）
#   avs3dec/include/ avs3Decoder/include/*.h    4 个（对外接口）
#   avs3dec/common/  libavs3_common/*.c|*.h     23 + 11 个（DSP/神经网络/ROM 表）
#
# ## model.h 是 407KB 的头文件，里面是 unsigned char g_model[] 内嵌模型数组，
#    不是外部 model.bin。所以不需要额外打包模型文件。
#
# ## 不依赖 libVMFPlatForm.so：objdump -p 实测预编译库的 NEEDED 只有
#    libc++_shared / libm / libdl / libc。VMF 只是 AudioFilter 用的。
# ============================================================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(AVS3DEC_DIR ${CMAKE_SOURCE_DIR}/avs3dec)

    file(GLOB AVS3DEC_SRCS
        ${AVS3DEC_DIR}/src/*.c
        ${AVS3DEC_DIR}/common/*.c)

    # ★不要排除 avs3_write_wav.c（2026-10-26 实测）：
    #   avs3_dec_lib.c 的 Avs3DecoderLibOpenWavFile() 直接调用了它里面的
    #   WriteWavHeader()，排掉会导致 ld.lld 报 undefined symbol: WriteWavHeader。
    #   它确实引用 stdio 的 fopen/fwrite，但那些在 Android 上都有，不会造成问题。

    add_library(avs3dec STATIC ${AVS3DEC_SRCS})

    target_include_directories(avs3dec PUBLIC
        ${AVS3DEC_DIR}/include
        ${AVS3DEC_DIR}/common)

    # 官方源码是 C99 风格（大量声明在语句中间、for(int i=...)),
    # 用 gnu99 最稳；显式关掉会让老式写法直接报错。
    target_compile_options(avs3dec PRIVATE
        -O2
        -std=gnu99
        -Wno-everything)

    target_link_libraries(avs3dec PUBLIC m)

    message(STATUS "AVS3 自编译解码器：${ANDROID_ABI}，${AVS3DEC_SRCS} 个源文件")

    # ★关键：必须把静态库**真正链接**进桥，否则 CMake 只会「定义」目标而
    #   不会编译它（只有被依赖的目标才参与构建）。
    #   这一步同时把 NEURALSTEM_AVS3_BUILTIN 打开，让桥走自编译符号而不是 dlopen。
    target_compile_definitions(neuralstem_avs3 PRIVATE NEURALSTEM_AVS3_BUILTIN=1)
    target_include_directories(neuralstem_avs3 PRIVATE
        ${AVS3DEC_DIR}/include
        ${AVS3DEC_DIR}/common)
    target_link_libraries(neuralstem_avs3 avs3dec)
else()
    message(STATUS "AVS3 自编译解码器仅支持 arm64-v8a，当前 ${ANDROID_ABI} 跳过（桥回落 dlopen）")
endif()
