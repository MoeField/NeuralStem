# Debian trixie = F-Droid buildserver 当前基线（官方文档要求 >= trixie）。
# 该镜像只提供 JDK/Gradle/Android SDK/F-Droid 工具；SDK/NDK 放在 compose 命名卷里，
# 避免每次重建镜像都重新下载数 GB。
FROM --platform=linux/amd64 debian:trixie-slim

ARG ANDROID_CMDLINE_TOOLS=commandlinetools-linux-13114758_latest.zip
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/${ANDROID_CMDLINE_TOOLS}

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV GRADLE_USER_HOME=/root/.gradle
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        ca-certificates \
        wget \
        curl \
        unzip \
        git \
        python3 \
        python3-pip \
        fdroidserver \
        rsync \
        sudo \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && wget -q "${CMDLINE_TOOLS_URL}" -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip \
    && chmod +x ${ANDROID_HOME}/cmdline-tools/latest/bin/*

COPY setup-sdk.sh /usr/local/bin/setup-sdk
RUN chmod +x /usr/local/bin/setup-sdk

WORKDIR /workspace
CMD ["bash", "-lc", "/usr/local/bin/setup-sdk && ./gradlew :app:assembleRelease"]
