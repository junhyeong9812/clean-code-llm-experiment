# 에이전트 실행 이미지(ADR-0005 §6.1): JDK 21 · Node · Claude CLI(버전 고정) · Gradle 배포판(오프라인 wrapper용)
FROM eclipse-temurin:21-jdk-noble
ARG CLI_VERSION
ARG GRADLE_VERSION=8.14.4
ARG UID=1000
ARG GID=1000
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm git ripgrep curl unzip postgresql-client ca-certificates \
 && rm -rf /var/lib/apt/lists/*
RUN npm install -g "@anthropic-ai/claude-code@${CLI_VERSION}" && npm cache clean --force
# wrapper가 내려받지 않도록 배포판 zip을 이미지에 둔다(러너가 사본의 distributionUrl을 이 파일로 바꾼다)
RUN mkdir -p /opt/gradle && curl -fsSL -o "/opt/gradle/gradle-${GRADLE_VERSION}-bin.zip" \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
# 베이스 이미지의 ubuntu 사용자(uid 1000)를 지우고 호스트 uid로 실행 사용자를 만든다(사본 파일 소유권 일치)
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && groupadd -g "${GID}" agent && useradd -m -u "${UID}" -g "${GID}" -d /home/agent agent
ENV DISABLE_AUTOUPDATER=1 CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
USER agent
WORKDIR /work
