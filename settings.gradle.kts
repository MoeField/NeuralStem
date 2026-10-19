// 仓库来源说明(踩坑记录见 doc/.critical_steps.md):
// - Gradle 依赖走公共镜像（阿里云 google 镜像直连），无需本机代理。
// - 历史踩坑：某些“URL 前缀型”代理会对 gzip 二进制响应做文本消毒，
//   导致 media3 的 AAR 损坏（invalid zip / U+FFFD）。故仓库地址不使用任何
//   自建/私有代理前缀；如本机需代理访问 maven 中央仓，请自行配置 CI/Shell 代理。
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
