#!/usr/bin/env bash
set -euo pipefail

# jt400-proxy-server launcher (Unix / macOS / Linux)
# Usage: ./run.sh [extra jvm args]
#
# Recommended: export the AS400_* and HIKARI_* variables (see README and application.properties.example)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR=$(ls -1 target/jt400-proxy-server-*.jar 2>/dev/null | head -1 || true)

if [[ -z "${JAR}" ]]; then
  echo "ERROR: No jar found in target/. Run 'mvn clean package' first." >&2
  exit 1
fi

# Sensible defaults if not provided via env
: "${PROXY_TCP_PORT:=9400}"
: "${HIKARI_MAX_POOL_SIZE:=20}"

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC}"

echo "Starting jt400-proxy-server..."
echo "  JAR: $JAR"
echo "  TCP port: ${PROXY_TCP_PORT}"
echo "  Hikari max pool: ${HIKARI_MAX_POOL_SIZE}"
echo "  JAVA_OPTS: ${JAVA_OPTS}"
echo

exec java $JAVA_OPTS \
  -Dproxy.tcp.port="${PROXY_TCP_PORT}" \
  -Dhikari.maxPoolSize="${HIKARI_MAX_POOL_SIZE}" \
  -jar "$JAR" "$@"
