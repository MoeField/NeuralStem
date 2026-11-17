# 自建 F-Droid 兼容源

> **注意：`fdroid/local/` 只是本地生成目录，不是仓库地址。**
> 用户应添加 GitHub Pages URL：
> `https://neuralstem.miku.pub/fdroid/repo/`
> 本地目录不会被 F-Droid 客户端访问，也不应该把绝对路径写进 F-Droid 配置。

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

已发布到本仓库的 `gh-pages` 分支：

```text
https://neuralstem.miku.pub/fdroid/repo/
```

首次发布后，需要仓库管理员在 GitHub：

```text
Settings → Pages → Build and deployment
Source: Deploy from a branch
Branch: gh-pages  /  (root)
```

启用后上面的 URL 才可访问。`gh-pages` 根目录已加 `.nojekyll`。

用户流程：

1. 安装 F-Droid 客户端
2. 设置 → 仓库 → 添加上述 URL
3. 刷新后从自建源安装/更新

注意：不要使用 `raw.githubusercontent.com` 作为仓库 URL；
index 里的 APK 地址按 Pages 地址生成。

## 发布到 GitHub Pages

```bash
FDROID_BIN=/tmp/fdroidvenv/bin/fdroid \
  ./fdroid/scripts/update-local-repo.sh \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk
./fdroid/scripts/publish-gh-pages.sh
```

首次需要在 GitHub 仓库设置里启用 Pages：

```text
Settings → Pages → Deploy from a branch
Branch: gh-pages / (root)
```

## 安全

- `fdroid/local/` 已在 `.gitignore` 中。
- repo signing key 与 App release key 是两把不同密钥。
- repo key 丢失后无法继续给老用户签同一个源；必须备份。
- 自建源没有官方审核，模型/第三方许可责任仍在发布者。

---

## 自动发布（GitHub Actions）

workflow 文件：

```text
.github/workflows/fdroid-publish.yml
```

触发方式：

- GitHub Release **published** 后自动执行
- 或在 Actions 页面手动 `Run workflow`，输入 `v0.2.28` 这类 tag

流程：

```text
release asset: app-arm64-v8a-release.apk
  → 从 gh-pages 取当前 repo
  → 删除旧 NeuralStem APK
  → fdroid update（用 repo signing key 签 index）
  → 把 fdroid/repo/ 推回 gh-pages
```

不会生成新 key；没有 secret 时直接失败，不会污染现有源。

### 第一次配置 Secrets

在仓库：

```text
Settings → Secrets and variables → Actions → Repository secrets
```

添加：

| Secret | 内容 |
|---|---|
| `FDROID_KEYSTORE_BASE64` | `fdroid/local/keystore.p12` 的 base64，单行 |
| `FDROID_KEYSTORE_PASSWORD` | repo keystore 密码（`keystorepass`） |
| `FDROID_KEY_ALIAS` | `fdroid/local/config.yml` 里的 `repo_keyalias` |
| `FDROID_KEY_PASSWORD` | repo key 密码（`keypass`）；与上面相同可省略 |

生成 base64：

```bash
base64 < fdroid/local/keystore.p12 | tr -d '\n'
```

不要把 keystore、密码、base64 贴到 issue/聊天/仓库。

### 验证 workflow

加完 secrets 后：

1. Actions → `Publish F-Droid repo` → `Run workflow`
2. 输入已有 release tag，例如 `v0.2.28`
3. 成功后检查：

```text
https://neuralstem.miku.pub/fdroid/repo/index-v2.json
```

里面的 versionCode 应与该 release 一致。

### 注意

- `gh-pages` 的 `CNAME` / `.nojekyll` 不会被覆盖；workflow 只替换 `fdroid/repo/`。
- 自动源只保留最新 APK；旧版本从 index 中移除，但已安装用户仍能正常升级。
- repo signing key 丢失会导致老用户无法继续从同一源更新；请继续备份 `fdroid/local/keystore.p12` 和 `config.yml`。
