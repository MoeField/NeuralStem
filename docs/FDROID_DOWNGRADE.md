# F-Droid 降级可行性 spike

> 分支：`spike/fdroid-downgrade`  
> 基线：`spike/fdroid-trusted-native`（JitPack sherpa、tracked .so 已移除）

## 目标

验证「AGP 9.5 / Gradle 9.8.2 / compileSdk 37 / NDK 30 / CMake 4.1.2」
能否回退到 F-Droid 当前工具链范围。

## 实际降级结果

| 项 | 原 | 降级后 |
|---|---|---|
| Gradle | 9.8.2 | **8.14.2** |
| AGP | 9.5.0 | **8.13.0** |
| compileSdk | 37 | **36** |
| targetSdk | 37 | **36** |
| JDK | CI 21 | **17** |
| Kotlin Android 插件 | AGP 9 内置 | **显式 `org.jetbrains.kotlin.android`** |
| AndroidX core | 1.19.0 | **1.16.0** |
| lifecycle | 2.11.0 | **2.9.4** |
| activity-compose | 1.13.0 | **1.10.1** |
| Compose BOM | 2026.02.01 | **2025.09.00** |
| coroutines | 1.11.0 | **1.10.2** |
| Media3 | 1.11.1 | **保持 1.11.1** |
| NDK | 30.0.16248370 | **保持**（本地编译通过） |
| CMake | 4.1.2 | **保持**（本地编译通过） |

其它改动：

- 删除 `gradle/gradle-daemon-jvm.properties`（Gradle 9 的 daemon JVM 25 配置，Gradle 8 不兼容）
- `gradle.properties` 增加 `android.useAndroidX=true`
- Java/Kotlin target 统一为 **17**
- `dependenciesInfo { includeInApk=false; includeInBundle=false }`
- AGP 8 DSL：`optimization {}` → `isMinifyEnabled` / `isShrinkResources`

## 验证结果

- `:app:compileDebugKotlin`：**通过**
- `:app:assembleDebug`：**通过**
- `:app:testDebugUnitTest`：**467/0**
- `:app:assembleRelease`：**通过**
- 真机 smoke：v0.2.21 降级构建可启动、播放、edge 分离、自动 2 轨
- 未做完整 UI/长跑回归

## 结论

**构建栈降级本身可行**，不需要改动业务源码。

但有两个重要修正：

### 1. 不是只降 Gradle

必须同时：

- 加回 Kotlin Android plugin（AGP 9 是内置 Kotlin）
- 删除 Gradle 9 daemon JVM 配置
- 加 `android.useAndroidX=true`
- 降 AndroidX/Compose 版本，因为 2026 版本要求 compileSdk 37/AGP 9.1
- 统一 Java/Kotlin JVM target 17
- AGP 8 DSL 替换

### 2. NDK/CMake 本地不用降

在 AGP 8.13 + NDK 30 + CMake 4.1.2 下本地完整编译通过。  
是否要降只看 F-Droid buildserver 实际装了哪些版本；不是编译正确性的必要条件。

## 仍卡住官方 F-Droid 的点

### buildserver SDK

fdroidserver 2.4.5 的 buildserver provision 列表仍只到 Android 33。
降级后 compileSdk 是 **36**，仍高于 33。

所以：

- 本地验证通过
- 但官方 buildserver 仍不能直接构建 compileSdk 36
- 除非 F-Droid 更新 buildserver，或继续降到 API 33 并降整个 AndroidX/Compose 栈

### Gradle

降级后是 Gradle 8.14.2，fdroidserver 2.4.5 的 hash 表支持它，**这一关已消除**。

## 关于那份降级描述的核对

大部分方向正确，但有几处需要纠正：

- ❌ `gradle: - 8.x`：F-Droid metadata 的 `gradle` 不是 Gradle 版本，而是 **flavor 列表**；写 `8.x` 会被当成 flavor。
- ❌ `cmake: 3.22.1`：metadata 没有 `cmake` 字段；CMake 版本由项目/AGP/SDK 决定。
- ✅ `dependenciesInfo` 关闭：正确，F-Droid 会拒绝 DependencyInfoBlock。
- ✅ JDK 17：与当前 buildserver 基线一致。
- ✅ compileSdk 要跟着 AGP 上限走：AGP 8.13 最高 API 36.1。
- ✅ 需要降 AndroidX/Compose，而不是只降 AGP。
- ⚠️ NDK/CMake 不一定需要降；取决于 buildserver 实际提供版本。
- ⚠️ 官方 buildserver 的 SDK 上限（33）是比 Gradle 更硬的阻塞，描述里没有覆盖。

## 建议

- 如果要走官方 F-Droid：先解决 buildserver SDK 36 支持；构建栈降级可以作为准备分支。
- 如果不想继续降依赖到 API 33：等 F-Droid 更新 buildserver。
- 如果现在就要发：自建 F-Droid 源，保留当前主分支构建栈。
