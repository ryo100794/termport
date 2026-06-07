FROM ubuntu:24.04

ENV HOME=/root \
    TERM=xterm-256color \
    DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
    ANDROID_API=26 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="/usr/lib/jvm/java-21-openjdk-arm64/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        box64 \
        ca-certificates \
        clang \
        curl \
        default-jdk-headless \
        file \
        gcc-aarch64-linux-gnu \
        git \
        libegl1-mesa-dev \
        libgles2-mesa-dev \
        libvulkan-dev \
        lld \
        make \
        unzip \
    && find "$JAVA_HOME/conf" -type l -print | while IFS= read -r link; do \
        target="$(readlink "$link")"; \
        case "$target" in \
          /*) if [ -e "$target" ]; then rm -f "$link" && cp -a "$target" "$link"; fi ;; \
        esac; \
       done \
    && mkdir -p /etc/ssl/certs/java \
    && rm -f /etc/ssl/certs/java/cacerts \
    && find /usr/share/ca-certificates -type f -name '*.crt' -print | sort | while IFS= read -r crt; do \
        alias="ca$(printf '%s' "$crt" | cksum | cut -d' ' -f1)"; \
        JAVA_TOOL_OPTIONS= "$JAVA_HOME/bin/keytool" -importcert -noprompt -trustcacerts -alias "$alias" -file "$crt" \
          -keystore /etc/ssl/certs/java/cacerts -storepass changeit >/dev/null 2>&1 || true; \
       done \
    && test -s /etc/ssl/certs/java/cacerts \
    && rm -f "$JAVA_HOME/lib/security/cacerts" \
    && cp -a /etc/ssl/certs/java/cacerts "$JAVA_HOME/lib/security/cacerts" \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.3.11579264
ENV LD_LIBRARY_PATH="${JAVA_HOME}/lib:${JAVA_HOME}/lib/server"
ENV JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
      -o /tmp/android-cmdline-tools.zip \
    && unzip -q /tmp/android-cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && rm -f /tmp/android-cmdline-tools.zip \
    && rm -rf "$ANDROID_HOME/cmdline-tools/latest" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
    && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "ndk;26.3.11579264" \
    && mkdir -p /opt/android-aapt2-agp /root/android-aapt2-agp \
    && printf '%s\n' \
        '#!/bin/sh' \
        'exec /usr/bin/box64 /opt/android-sdk/build-tools/34.0.0/aapt2 "$@"' \
        > /opt/android-aapt2-agp/aapt2 \
    && chmod 0755 /opt/android-aapt2-agp/aapt2 \
    && cp /opt/android-aapt2-agp/aapt2 /root/android-aapt2-agp/aapt2 \
    && printf '%s\n' \
        '#!/bin/sh' \
        'mkdir -p "${HOME:-/root}/android-aapt2-agp"' \
        'cp /opt/android-aapt2-agp/aapt2 "${HOME:-/root}/android-aapt2-agp/aapt2"' \
        'chmod 0755 "${HOME:-/root}/android-aapt2-agp/aapt2"' \
        > /usr/local/bin/termport-prepare-android-build-tools \
    && chmod 0755 /usr/local/bin/termport-prepare-android-build-tools \
    && printf '%s\n' \
        'export ANDROID_HOME=/opt/android-sdk' \
        'export ANDROID_SDK_ROOT=/opt/android-sdk' \
        'export ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.3.11579264' \
        'export PATH=/usr/lib/jvm/java-21-openjdk-arm64/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
        'termport-prepare-android-build-tools 2>/dev/null || true' \
        > /etc/profile.d/termport-android-sdk.sh

WORKDIR /workspace
CMD ["/bin/bash", "-l"]
