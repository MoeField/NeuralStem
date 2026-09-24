// 仓库代理策略(2026-02 定,踩坑记录见 doc/.critical_steps.md):
// - 本机系统 HTTP 代理(127.0.0.1:26561,Steam++)会做 TLS 解密,Gradle JVM 校验证书失败(PKIX)
// - 本机 SOCKS5(127.0.0.1:1080,sslocal)能通但会触发 IDE 代理认证弹窗,弃用
// - 曾用方案:https://private-proxy.invalid/ 前缀代理 —— Task 1 时发现其对 gzip 压缩响应做
//   UTF-8 文本消毒,导致 media3 全家 AAR 损坏(invalid zip / U+FFFD 替换字符),
//   且 6G 环境下稳定复现,已弃用(2026-02,详见 .critical_steps.md)
// - 现方案:阿里云 google 镜像直连(https://maven.aliyun.com/repository/google),
//   实测直连无需代理;若某包在 aliyun 缺失,可临时改回 prox 或手动下载进本地仓
pluginManagement {
    repositories {
        google {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            url = uri("https://maven.aliyun.com/repository/google")
        }
        mavenCentral()
    }
}

rootProject.name = "NeuralStem"
include(":app")
// M4 上限验证：独立 bench 模块（只依赖带 QNN/XNNPACK EP 的 ORT，不引 sherpa）
include(":bench")
