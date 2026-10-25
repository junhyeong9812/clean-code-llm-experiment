#!/usr/bin/env bash
# 에이전트 이미지 빌드: experiment/runner/image/build.sh [CLI 버전, 기본 2.1.308]
set -euo pipefail
ver=${1:-2.1.308}
cd "$(dirname "$0")"
docker build --build-arg CLI_VERSION="$ver" --build-arg UID="$(id -u)" --build-arg GID="$(id -g)" \
  -t "ccle-agent:cli-$ver" .
docker run --rm "ccle-agent:cli-$ver" claude --version
