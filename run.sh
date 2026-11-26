#!/usr/bin/env bash
# Запуск SpaceAce на Linux и WSL (окно открывается через WSLg). Аргумент --no-tests — запуск без тестов.
set -euo pipefail
cd "$(dirname "$0")"

# JDK 21: JAVA_HOME, затем путь из jdk-path.txt, затем ~/.jdks/jdk-21*.
jdk_ok() { [ -n "${1:-}" ] && [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'; }
JDK=""
if jdk_ok "${JAVA_HOME:-}"; then JDK="$JAVA_HOME"; fi
if [ -z "$JDK" ] && [ -f jdk-path.txt ] && jdk_ok "$(head -n1 jdk-path.txt)"; then JDK="$(head -n1 jdk-path.txt)"; fi
if [ -z "$JDK" ]; then
  for d in "$HOME"/.jdks/jdk-21*; do jdk_ok "$d" && JDK="$d" && break; done
fi
if [ -z "$JDK" ]; then
  echo "JDK 21 not found. Install it, e.g.:"
  echo "  mkdir -p ~/.jdks && curl -sSL 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse' | tar xz -C ~/.jdks"
  exit 1
fi
export JAVA_HOME="$JDK"

if [ "${1:-}" = "--no-tests" ]; then
  ./gradlew desktop:run -x test --console=plain -q
else
  ./gradlew core:test desktop:run --console=plain -q
fi
