#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
MVN_VERSION="3.9.9"
MVN_HOME="$BASE_DIR/.mvn/apache-maven-$MVN_VERSION"
if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  mkdir -p "$BASE_DIR/.mvn"
  URL="https://archive.apache.org/dist/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz"
  echo "Downloading Maven $MVN_VERSION..." >&2
  curl -fsSL "$URL" -o "$BASE_DIR/.mvn/maven.tgz"
  tar -xzf "$BASE_DIR/.mvn/maven.tgz" -C "$BASE_DIR/.mvn"
fi
exec "$MVN_HOME/bin/mvn" "$@"
