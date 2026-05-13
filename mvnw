#!/usr/bin/env bash
# ------------------------------------------------------------------
# bromo Maven wrapper (POSIX).
# Bootstraps Apache Maven into ~/.m2/bootstrap on first run, then
# delegates to it. Requires JAVA_HOME set or java on PATH.
# ------------------------------------------------------------------
set -e

MVN_VER=3.9.9
MVN_HOME="$HOME/.m2/bootstrap/apache-maven-$MVN_VER"

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "$HOME/.jdks/temurin-25.0.3" ]; then
        export JAVA_HOME="$HOME/.jdks/temurin-25.0.3"
    elif command -v javac >/dev/null 2>&1; then
        :  # mvn discovers from PATH
    else
        echo "Error: JAVA_HOME not set and no javac on PATH." >&2
        exit 1
    fi
fi

if [ ! -x "$MVN_HOME/bin/mvn" ]; then
    echo "Bootstrapping Apache Maven $MVN_VER..."
    mkdir -p "$HOME/.m2/bootstrap"
    tmp="$(mktemp -t mvn-bootstrap.XXXXXX.zip)"
    curl -fsSL -o "$tmp" \
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VER/apache-maven-$MVN_VER-bin.zip"
    unzip -q -o "$tmp" -d "$HOME/.m2/bootstrap"
    rm "$tmp"
    chmod +x "$MVN_HOME/bin/mvn"
fi

exec "$MVN_HOME/bin/mvn" "$@"
