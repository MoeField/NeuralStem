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
# 本桥**只做 dlopen**，不含任何 AVS3 解码器代码（所以可归 MIT）。
# 运行时依次找：
#   1. libavs3self.so      —— tools/fetch-avs3.sh + 本 CMake 编出的自编译版
#   2. libAVS3AudioDec.so  —— 预编译版（AArch64 有代码生成 bug，仅作回退）
# 都找不到 → nativeInit 返回失败码，App 其余功能不受影响。
#
# ★ WITH_NCM（2026-10-26）：整个 AVS3 链路**只在 NCM 构建里编**。
#   之前这里无条件下 add_library，导致公开版 APK 里也带着
#   libneuralstem_avs3.so / libavs3self.so / libAVS3AudioDec.so / libVMFPlatForm.so
#   —— 而后两个是 UWA 会员制授权，**不可再分发**。
#   该开关由 build.gradle.kts 通过 externalNativeBuild.arguments 传入。
# ============================================================================
if(WITH_NCM AND ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(neuralstem_avs3 SHARED avs3_audio_bridge.c)
    target_link_libraries(neuralstem_avs3 log dl)
    target_compile_options(neuralstem_avs3 PRIVATE -O2)
elseif(NOT WITH_NCM)
    message(STATUS "WITH_NCM=OFF → 不编 AVS3 桥（公开版不含解码器）")
else()
    message(STATUS "AVS3 桥仅支持 arm64-v8a，当前 ${ANDROID_ABI} 跳过")
endif()

# ============================================================================
# ★AVS3-P3 解码器（可选，需自行拉取源码）
#
# ## 为何不在仓库里
# AVS3-P3 解码器的参考实现（来自 github.com/Dreamello/av3a）并不采用
# MIT/Apache/GPL 等常见开源许可，而是《世界超高清视频产业联盟代码共享政策》：
#     第二条  仅适用于联盟会员，由联盟向提出申请的会员进行许可
#     第六条  除“有条件转发”外不能以任何形式转发；而有条件转发只允许
#             会员将编译后的目标代码交给自己的下游客户
#     第八条  不包含专利权许可
# ⇒ 我们既不是会员也不是下游客户，**源码和目标代码都不能再分发**。
#
# ## 那本地怎么用
#   跑 tools/fetch-avs3.sh 拉到 avs3dec/，CMake 自动发现并编成
#   独立的 libavs3self.so（不是静态链进桥 —— 那样整个桥都会变成二次代码）。
#   avs3dec/ 已在 .gitignore 里，不会进版本控制。
#
# ## 没有源码时
#   整段跳过，不报错。clone 下来直接 assembleDebug 必须能成功。
# ============================================================================
set(AVS3DEC_DIR ${CMAKE_SOURCE_DIR}/avs3dec)

if(WITH_NCM AND ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS ${AVS3DEC_DIR}/src/decoder.c)
    file(GLOB AVS3DEC_SRCS
        ${AVS3DEC_DIR}/src/*.c
        ${AVS3DEC_DIR}/common/*.c)

    # ★不要排除 avs3_write_wav.c（2026-10-26 实测）：
    #   avs3_dec_lib.c 的 Avs3DecoderLibOpenWavFile() 直接调用了它里面的
    #   WriteWavHeader()，排掉会导致 ld.lld 报 undefined symbol: WriteWavHeader。

    # 编成**独立的共享库**（而不是静态库 + 链进桥）：
    #   桥通过 dlopen 加载，两者在编译期无依赖，边界干净。
    add_library(avs3self SHARED ${AVS3DEC_SRCS})

    target_include_directories(avs3self PUBLIC
        ${AVS3DEC_DIR}/include
        ${AVS3DEC_DIR}/common)

    # 官方源码是 C99 风格（大量声明在语句中间、for(int i=...)），用 gnu99 最稳。
    target_compile_options(avs3self PRIVATE
        -O2
        -std=gnu99
        -Wno-everything)

    target_link_libraries(avs3self m)

    # 输出名改成 libavs3self.so，与预编译的 libAVS3AudioDec.so 共存不冲突。
    set_target_properties(avs3self PROPERTIES OUTPUT_NAME "avs3self")

    # ★桥需要 dat 结构体头文件（读 numChansOutput / outputFs 做下混）：
    #   有源码时用真实结构体字段，没源码时回退到 static_assert 实测的偏移。
    target_include_directories(neuralstem_avs3 PRIVATE
        ${AVS3DEC_DIR}/include
        ${AVS3DEC_DIR}/common)
    target_compile_definitions(neuralstem_avs3 PRIVATE NEURALSTEM_AVS3_HEADERS=1)

    message(STATUS "AVS3 自编译解码器：${ANDROID_ABI}，${AVS3DEC_SRCS} 个源文件 → libavs3self.so")
elseif(NOT WITH_NCM)
    message(STATUS "WITH_NCM=OFF → 不编 AVS3 自编译解码器")
else()
    # ★这里只是“静默跳过”，不算错误。
    #   clone 下来直接 assembleDebug 必须能成功，否则新用户第一步就撞墙。
    message(STATUS "AVS3 解码器源码不存在（跑 tools/fetch-avs3.sh 可启用）")
    message(STATUS "  → NCM 解密/导入仍可用；AVS3(av3a) 编码的曲目将无法播放")
endif()

# ============================================================================


