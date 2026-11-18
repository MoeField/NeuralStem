# 自建 F-Droid 兼容源

> **`fdroid/local/` 只是本地生成目录，不是用户地址。**
> 用户添加：
> `https://neuralstem.miku.pub/fdroid/repo/`
> 不要使用 `raw.githubusercontent.com`，也不要把本机绝对路径写进 F-Droid 配置。

当前源由 GitHub Actions 自动发布，最新验证版本：**0.2.28 (48)**。

## 用户安装

1. 安装 F-Droid 客户端
2. 设置 → 仓库 → 添加：

```text
https://neuralstem.miku.pub/fdroid/repo/
```

3. 刷新后从自建源安装/更新 NeuralStem

仓库地址和签名 key 固定；后续发版只需要刷新，不用重新添加。

## 自动发布（当前主流程）

workflow：

```text
.github/workflows/fdroid-publish.yml
```

触发：

- Android CI 在 `v*` tag 上构建成功并发布 Release 后自动执行
- 或 Actions → `Publish F-Droid repo` → `Run workflow`，输入 release tag

> 不监听 `release: published`：Android CI 用 `GITHUB_TOKEN` 创建 Release 时，
> 该事件不会再触发新的 workflow（GitHub 防递归）。

流程：

```text
release asset: app-arm64-v8a-release.apk
  → 取最新 gh-pages 的 repo
  → 删除旧 NeuralStem APK
  → fdroid update（repo signing key 签 index）
  → 只替换 fdroid/repo/ 并推回 gh-pages
```

已验证：

```text
run 44134749072: success
gh-pages commit 1fc4b79: publish: NeuralStem F-Droid repo v0.2.28
```

自动发布不会生成新 key；secret 缺失或校验失败时直接失败，不会污染现有源。

workflow 在生成 index 前还会校验：

- release tag 必须等于 APK 的 `versionName`
- APK 签名证书必须是当前 App release 证书（默认 `85a5c9...`）

若 release secrets 缺失导致 CI 回退 debug 签名，这一步会直接拒绝发布。
证书轮换时同时更新 Repository variable `EXPECTED_APK_CERT_SHA256`。

### Repository Secrets

仓库已配置以下 secrets；如需恢复/迁移，参见此表：

| Secret | 内容 |
|---|---|
| `FDROID_KEYSTORE_BASE64` | `fdroid/local/keystore.p12` 的 base64，单行 |
| `FDROID_KEYSTORE_PASSWORD` | repo keystore 密码（`keystorepass`） |
| `FDROID_KEY_ALIAS` | `fdroid/local/config.yml` 的 `repo_keyalias`，当前为 `neuralstem-repo` |
| `FDROID_KEY_PASSWORD` | repo key 密码（`keypass`）；与上面相同可省略 |

生成 base64：

```bash
base64 < fdroid/local/keystore.p12 | tr -d '\n'
```

注意：

- 不要包含终端提示符（常见是复制时带上的末尾 `%`）
- 不要包含引号、文件路径、密码
- workflow 会去掉空白/CRLF 并校验 base64、alias、密码；但 secret 本身应保持干净
- repo key 与 App release 签名 key 是**两套不同密钥**

App APK 签名 secrets 仍是：

```text
KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
```

不要和上面的 `FDROID_*` 混用。

## 手动发布（恢复/本地调试）

只在 workflow 不可用时使用。首次初始化：

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
  keystore.p12     # repo index 签名密钥，**必须备份**
  metadata/
  repo/            # 发布内容
```

更新 APK + 发布：

```bash
./gradlew :app:assembleRelease
FDROID_BIN=/tmp/fdroidvenv/bin/fdroid \
  ./fdroid/scripts/update-local-repo.sh \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk
./fdroid/scripts/publish-gh-pages.sh
```

GitHub Pages 已配置为 `gh-pages / (root)`；`CNAME`、`.nojekyll` 由发布脚本保留。

## 安全

- `fdroid/local/` 已在 `.gitignore` 中。
- repo signing key 丢失后无法继续给老用户签同一个源；必须备份 `keystore.p12` 和 `config.yml`。
- 自建源没有官方审核，模型/第三方许可责任仍在发布者。
- 不要把 keystore、密码、base64 贴到 issue/聊天/仓库。
