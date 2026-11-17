# 自建 F-Droid 兼容源

不依赖 F-Droid 官方 buildserver 或审核。用户手动添加仓库地址后，
即可在 F-Droid 客户端中安装/更新 NeuralStem。

## 为什么这样做

- 保留现有 APK 签名和包名，不需要卸载现有 GitHub 版本。
- 不需要降级 AGP/Gradle，也不需要等官方 buildserver 支持 SDK 37。
- 不进入官方 F-Droid 主库，但仍可被 F-Droid 客户端管理。

## 首次初始化

```bash
python3 -m venv /tmp/fdroidvenv
/tmp/fdroidvenv/bin/pip install fdroidserver==2.4.5

export ANDROID_HOME=/path/to/android-sdk
export FDROID_BIN=/tmp/fdroidvenv/bin/fdroid
./fdroid/scripts/init-local-repo.sh
```

会生成：

```text
fdroid/local/
  config.yml       # 含 repo keystore 密码，**不要提交**
  keystore.jks     # repo index 签名密钥，**必须备份**
  metadata/
  repo/            # 发布内容
```

## 更新 APK

先构建 release APK（保持当前 release 签名）：

```bash
./gradlew :app:assembleRelease
FDROID_BIN=/tmp/fdroidvenv/bin/fdroid \
  ./fdroid/scripts/update-local-repo.sh \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 发布

把 `fdroid/local/repo/` 内容放到静态 HTTPS 托管，例如
GitHub Pages 的 `gh-pages` 分支下的 `fdroid/repo/`。

仓库 URL（示例）：

```text
https://moefield.github.io/NeuralStem/fdroid/repo/
```

用户流程：

1. 安装 F-Droid 客户端
2. 设置 → 仓库 → 添加上述 URL
3. 刷新后从自建源安装/更新

## 安全

- `fdroid/local/` 已在 `.gitignore` 中。
- repo signing key 与 App release key 是两把不同密钥。
- repo key 丢失后无法继续给老用户签同一个源；必须备份。
- 自建源没有官方审核，模型/第三方许可责任仍在发布者。
