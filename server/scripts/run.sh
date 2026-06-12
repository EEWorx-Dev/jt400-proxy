#!/usr/bin/env bash
set -euo pipefail

# jt400-proxy-server launcher (Unix / macOS / Linux)
# Low-level launcher. start.sh is the recommended wrapper for most users.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$(basename "$SCRIPT_DIR")" == "scripts" || "$(basename "$SCRIPT_DIR")" == "bin" ]]; then
  SERVER_ROOT="$(dirname "$SCRIPT_DIR")"
else
  SERVER_ROOT="$SCRIPT_DIR"
fi

cd "$SERVER_ROOT"

# Source env file if present
for envfile in .env .env.local; do
  if [[ -f "$envfile" ]]; then
    set -a
    source "./$envfile"
    set +a
    break
  fi
done

# Locate JAR (dist or source layout)
JAR=""
if ls lib/jt400-proxy-server-*.jar >/dev/null 2>&1; then
  JAR=$(ls -1 lib/jt400-proxy-server-*.jar | head -1)
elif ls target/jt400-proxy-server-*.jar >/dev/null 2>&1; then
  JAR=$(ls -1 target/jt400-proxy-server-*.jar | head -1)
fi

if [[ -z "$JAR" ]]; then
  echo "ERROR: No jar found." >&2
  echo "Run 'mvn clean package' first (source tree) or use a distribution package." >&2
  exit 1
fi

: "${PROXY_TCP_PORT:=9400}"
: "${HIKARI_MAX_POOL_SIZE:=20}"

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC}"

echo "Starting jt400-proxy-server..."
echo "  JAR: $JAR"
echo "  TCP port: ${PROXY_TCP_PORT}"
echo "  Hikari max pool: ${HIKARI_MAX_POOL_SIZE}"
echo "  JAVA_OPTS: ${JAVA_OPTS}"

exec java $JAVA_OPTS \
  -Dproxy.tcp.port="${PROXY_TCP_PORT}" \
  -Dhikari.maxPoolSize="${HIKARI_MAX_POOL_SIZE}" \
  -jar "$JAR" "$@"
