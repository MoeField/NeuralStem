import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ==================== ★签名配置（2026-10-20） ====================
//
// ## 为何需要
// 之前 release 用 **debug 签名**（每台机器的 debug.keystore 不同）→
// 不同机器构建的包**不能互相覆盖安装**，也无法做自动更新（F-Droid/Obtainium
// 都靠签名一致性判定“同一个应用”）。
//
// ## 密钥从哪来
// 优先读 `keystore.properties`（**不入库**）；缺失则回退环境变（CI）。
// 两者都没有 → 回退 debug 签名（保证本地/CI 不因缺密钥而挂）。
//
// ## 生成密钥（一次性，详见 doc/release_signing.md）
//   keytool -genkeypair -keystore neuralstem.jks -alias neuralstem \
//     -keyalg RSA -keysize 4096 -validity 10000
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

/**
 * 密钥信息：文件优先，其次环境变（CI），都没有则 null。
 *
 * 为何支持环境变：GitHub Actions 上不能提交 keystore.properties，
 * 而是用 Secrets 解出 .jks 到临时目录 + 四个环境变。
 */
fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val signingStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")

/** 四项齐全 + 文件存在 → 才能用正式签名。 */
val hasReleaseSigning = listOf(
    signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword,
).all { it != null } && File(signingStoreFile!!).exists()

android {
    namespace = "pub.miku.neuralstem"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // ★minSdk 23 → 必须同时开 v1（Android 6 及以下只认 v1）+ v2/v3。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "pub.miku.neuralstem"
        minSdk = 23
        targetSdk = 37
        // ★版本号（2026-10-20 修正）：之前停在 10 / 0.1.0-alpha，但已发到 v0.2.1。
        //   versionCode 必须**严格递增**，否则 F-Droid/Obtainium 看不到更新。
        //   当前规则：semver 的 minor*10 + patch = 24（0.2.4 → 24）。
        versionCode = 24
        // ★versionName 可由 CI 覆盖（`-PversionName=0.2.4`，从 tag 名解出）。
        //   为何需要：tag 与 build.gradle 里的版本号容易不同步；
        //   以 tag 为准能保证“Release 页显示的版本 = 代码里的版本”。
        versionName = (project.findProperty("versionName") as String?) ?: "0.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // sherpa-onnx 官方 so 覆盖 4 个 ABI;模拟器用 x86_64,真机用 arm64-v8a。
            // 只打包这两个,减小 Spike APK 体积
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            /**
             * 有正式密钥 → 用正式签名（可跨机覆盖安装、可自动更新）。
             * 无 → 回退 debug（保证 CI/本地不挂；产物不能用于发布）。
             */
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ 未找到 keystore.properties / 密钥环境变量 → release 回退 debug 签名" +
                        "（产物仅供本地测试，不可发布）",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    /**
     * ★ABI 拆包（CI/发布用）。
     *
     * sherpa AAR 带 arm64 + x86_64 两套 so（单 ABI ~30MB）：
     * 合包 42MB；拆开后真机（arm64）包 ~26MB。
     *
     * `isUniversalApk = true` → 额外产一个合包，保证**本地开发仍能一包直装**
     * （`app-universal-debug.apk`），不影响现有 adb 调试流程。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // M2-C:关于页显示版本号/构建类型（AppShell.kt 用 BuildConfig）
        buildConfig = true
    }

    // ================= NCM 格式互操作性模块（默认不编译） =================
    // 一个可选的、源码级的“NCM 容器解析/解密”模块。
    // 依据开源社区对 NCM 格式的分析（ncmdump 系列）实现，属**格式互操作性**。
    //
    // 默认 **不编译、不打包**（发布 APK 不含此功能）。
    // 仅当显式传入 `-Pwith_ncm=true`（或 gradle.properties 里置位）时才加入构建：
    //   ./gradlew app:assembleDebug -Pwith_ncm=true
    //
    // 合规声明：绕过 DRM 在多数法域可能构成规避技术措施；仅供学习/调研，
    // 且仅用于用户自己已购/已下载的音乐文件在本地播放。
    val withNcm = (project.findProperty("with_ncm") as String?)?.toBoolean() ?: false
    if (withNcm) {
        sourceSets.getByName("main").java.srcDirs("src/ncm/java")
        sourceSets.getByName("main").kotlin.srcDirs("src/ncm/java")
        // 同步把 ncm 的单测也纳入（仅当启用时）
        sourceSets.getByName("test").java.srcDirs("src/ncmTest/java")
        sourceSets.getByName("test").kotlin.srcDirs("src/ncmTest/java")
    }
    defaultConfig {
        buildConfigField("boolean", "WITH_NCM", withNcm.toString())

        // ★把 with_ncm 传给 CMake：AVS3 桥 / 自编译解码器**只在 NCM 构建里编**。
        //
        // ## 为何必须有（2026-10-26 实测事故）
        // 以前 CMakeLists 里 `add_library(neuralstem_avs3 ...)` 是无条件的，
        // 结果公开版 release APK 里带着四个 AVS3 库：
        //   libAVS3AudioDec.so / libVMFPlatForm.so  ← UWA 会员制授权，**不可再分发**
        //   libavs3self.so / libneuralstem_avs3.so
        //
        // ## 为何写在 defaultConfig 里
        // `arguments` 只存在于 `defaultConfig.externalNativeBuild.cmake`；
        // 设在外层 `android.externalNativeBuild.cmake` 会 Unresolved reference。
        externalNativeBuild {
            cmake {
                arguments.add("-DWITH_NCM=" + if (withNcm) "ON" else "OFF")
            }
        }
    }

    // ================= ★Shizuku（仅 with_ncm 时） =================
    //
    // ## 用途
    // 网易云把 .ncm 存在 `/storage/XXXX/Android/data/com.netease.cloudmusic/files/...`，
    // Android 11+ 下**普通应用无法读**（SAF 选择器也进不去）。
    // Shizuku 提供“以 shell 权限运行”的能力 → 可以把文件 cp 到公共目录。
    //
    // ## 为何受 with_ncm 控制
    // 这是 NCM 功能的辅助工具，不属主线；不带 NCM 的构建不应多一个依赖。
    if (withNcm) {
        dependencies {
            implementation("dev.rikka.shizuku:api:13.1.5")
            implementation("dev.rikka.shizuku:provider:13.1.5")
        }
    }

    // Task 0e: StreamingSeparator 是纯逻辑类(分块/交叉淡化/权重归一化),
    // 但内部用 android.util.Log 做诊断输出。JVM 单测里那个类只有 "not mocked" 桩,
    // 一调用就抛 RuntimeException。开启默认返回值,让 Log 在单测中变为空操作,
    // 从而可以直接对拼接算法做逐样本断言(见 StreamingSeparatorTest)。
    // 仅影响单元测试,不影响 app 运行时的日志行为。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Task 0b Spike: JNI 胶水层(sherpa-onnx C API → Kotlin)
    // 本机已装 NDK 30.0.16248370(SA500 盘),AGP 首选 28.2 未安装,显式指定现有版本
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // SDK 里装的是 cmake 4.1.2(非 AGP 默认首选),显式指定让它被采用
            version = "4.1.2"
            // note：WITH_NCM 的 arguments 必须设在 defaultConfig.externalNativeBuild.cmake
            //   （见上方），设在这里会 Unresolved reference。
        }
    }

    packaging {
        jniLibs {
            // sherpa-onnx 的 so 未做 16KB 对齐(Android 15+ 限制),Spike 阶段放行
            useLegacyPackaging = true
            // AAR 与 cpp/sherpa-libs 各有一份 libsherpa-onnx-c-api.so,取其一
            pickFirsts += "**/libsherpa-onnx-c-api.so"
            // M4/S2：onnxruntime-android 与 sherpa AAR 各带一份同名 so，取其一
            pickFirsts += "**/libonnxruntime.so"

            // ★ 公开版必须排除 AVS3 解码器（2026-10-26 实测事故）
            //
            // `src/main/jniLibs/arm64-v8a/` 里放着两个 UWA 预编译库：
            //   libAVS3AudioDec.so / libVMFPlatForm.so
            // 它们是【世界超高清视频产业联盟代码共享政策】下的会员授权物，
            // 非 MIT/Apache/GPL，**不能再分发**。
            // 它们已被 .gitignore 排除（不进仓库），但只要目录里还在，
            // AGP 就会无条件打进**所有**变体。
            if (!withNcm) {
                excludes += listOf(
                    "**/libAVS3AudioDec.so",
                    "**/libVMFPlatForm.so",
                    "**/libavs3self.so",
                    "**/libneuralstem_avs3.so",
                )
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // U5: 歌单文件夹导入（SAF Tree 递归扫描）
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // --- Task 0b Spike: sherpa-onnx ONNX 音轨分离(选型切换理由见 doc/research_separation.md §5) ---
    // sherpa-onnx 未发布到 Maven Central;官方用法是直接引用 release 里的 AAR(已下载到 app/libs/,
    // 含 onnxruntime + c-api/cxx-api/jni 的 .so,覆盖 arm64-v8a/armeabi-v7a/x86/x86_64)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    // TFLite 回退路线已废弃(Task 0g 后归档):其 so 仅 4KB 对齐,不兼容 Android 15+ 的
    // 16KB 页设备;sherpa-onnx 路线实测全胜(18–25× 实时)。代码存档在
    // app/src/deprecated/(不参与编译),恢复方法见该目录 README.md
    // 协程:分块推理放后台线程,避免阻塞 UI(guide N2/N5)
    implementation(libs.kotlinx.coroutines.android)
    // lifecycle viewmodel-compose: Spike 页面状态管理
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Task 1: Media3/ExoPlayer 基础播放器(guide F0.x) ---
    // 多实例架构预埋:Task 0 已证分离输出为整轨 PCM,Task 4 的多 ExoPlayer 方案 A 成立。
    // 不引 media3-ui —— 控件自绘(为 Task 2 波形组件铺路)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    // V2 (guide N4): 后台播放 —— MediaSessionService 持有 player+session,Activity 经 MediaController 控制
    implementation(libs.media3.session)
    // M4/S2: Demucs 4-stem —— **不用** Maven 的 onnxruntime-android（其 Java 绑定与 sherpa
    // 自带的 libonnxruntime.so(1.28.2) 符号版本冲突）；改为自写 JNI（cpp/ort_probe.c）
    // 直接链接 sherpa 那份 .so。见 doc/.memory.md 阶段 M4 与 cpp/CMakeLists.txt

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

