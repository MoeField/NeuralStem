# 发布签名（Release Signing）

> 建立：2026-10-20 ｜ 适用：v0.2.1 之后的发布

## 为什么需要

之前 release 包用 **debug 签名**（`~/.android/debug.keystore`）。问题：

| 场景 | debug 签名 |
|---|---|
| 换一台机器构建 | 签名不同 → **不能覆盖安装** |
| 自动更新（F-Droid / Obtainium） | 靠签名判定"同一应用" → **完全不工作** |
| 用户从 debug 版切到 release 版 | 必须先卸载 → 私有目录数据全丢 |

## ★ 不可逆风险

> [!CAUTION]
> **keystore 文件 + 密码一起丢失 = 永久丧失该应用的更新能力。**
>
> 没有找回机制。唯一的"出路"是换 `applicationId`（`pub.miku.neuralstem` → `…2`）
> 重发一个**全新应用** —— 老用户收不到任何更新，永远停在旧版。
>
> 而且如果已经进了 F-Droid 仓库，换密钥后客户端会**拒绝**将其视为同一包的更新。

## 生成（一次性）

```bash
keytool -genkeypair -v \
  -keystore neuralstem.jks \
  -alias neuralstem \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

- `-validity 10000` ≈ 27 年（Google 要求 ≥25 年）
- 交互式会问：库密码、密钥密码、姓名/组织/城市/国家（可填 Unknown）

## 本地配置

在**项目根目录**建 `keystore.properties`（已在 `.gitignore` 中）：

```properties
storeFile=/绝对路径/neuralstem.jks
storePassword=库密码
keyAlias=neuralstem
keyPassword=密钥密码
```

`app/build.gradle.kts` 会读取它。**没有这个文件时自动回退 debug 签名**并打印警告
（保证 CI / 新克隆仓库不会因此构建失败）。

## CI 配置（GitHub Actions）

keystore 不能进仓库，用 Secrets 注入：

1. 把 `.jks` 转 base64 存进 Secret `KEYSTORE_BASE64`：
   ```bash
   base64 -i neuralstem.jks | pbcopy     # macOS 直接进剪贴板
   ```
2. 另外三个 Secret：`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`

CI 里的用法：

```yaml
- name: Decode keystore
  if: env.KEYSTORE_BASE64 != ''
  run: echo "$KEYSTORE_BASE64" | base64 -d > $RUNNER_TEMP/neuralstem.jks
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}

- name: Build release
  run: ./gradlew assembleRelease
  env:
    KEYSTORE_FILE: ${{ runner.temp }}/neuralstem.jks
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
```

`build.gradle.kts` 的取值顺序：**`keystore.properties` → 环境变量 → debug 回退**。

## 备份（★★ 最重要）

**3 份冗余，且密码与文件分离存放**：

| # | 位置 | 放什么 |
|---|---|---|
| 1 | iCloud Drive（加密压缩包） | `neuralstem-keystore.zip` |
| 2 | 离线介质（U 盘 / 移动硬盘） | 同上 |
| 3 | 密码管理器 | 3 个值（storePassword / keyAlias / keyPassword） |

加密压缩：

```bash
zip -er neuralstem-keystore.zip neuralstem.jks keystore.properties
```

> [!IMPORTANT]
> 压缩包的密码**不要**与 keystore 密码相同 —— 否则一份泄露 = 全泄露。

> [!WARNING]
> 不要用"iCloud Drive 同步文件夹"直接放 `.jks`：同步是双向的，本机误删会连云端一起删。
> 请用**不参与同步的归档位置**，或先加密压缩再上传。

## 版本号规则

`app/build.gradle.kts`：

```kotlin
versionCode = 21        // 必须严格递增，否则 F-Droid/Obtainium 看不到更新
versionName = "0.2.1"
```

当前规则：**`minor × 10 + patch`**（0.2.1 → 21；0.2.2 → 22；0.3.0 → 30）。

> 2026-10-20 修正：此前 `versionCode` 一直停在 10 / `0.1.0-alpha`，
> 但实际已发布到 v0.2.1 —— 这会让**任何**基于版本号的更新机制失效。

## 签名方案

`minSdk = 23` → 必须同时启用 **v1 + v2**：

- Android 6.0 及以下**只认 v1**（JAR 签名）
- Android 7+ 优先 v2/v3（APK Signature Scheme）
- 只开 v2 会导致老设备装不上

`build.gradle.kts` 里已显式 `enableV1Signing = true` + `enableV2Signing = true`。

## 验证签名是否正确

```bash
# 查看 APK 签名信息
apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 对比两个 APK 是否同一密钥（应输出相同 SHA-256）
apksigner verify --print-certs old.apk | grep SHA-256
apksigner verify --print-certs new.apk | grep SHA-256
```
