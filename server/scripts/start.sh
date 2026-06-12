#!/usr/bin/env bash
set -euo pipefail

# jt400-proxy-server start wrapper (Unix / macOS / Linux)
# This script is intended to be used both from source tree and from a distribution.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Determine server root (supports running from scripts/ or bin/ subdir in dist)
if [[ "$(basename "$SCRIPT_DIR")" == "scripts" || "$(basename "$SCRIPT_DIR")" == "bin" ]]; then
  SERVER_ROOT="$(dirname "$SCRIPT_DIR")"
else
  SERVER_ROOT="$SCRIPT_DIR"
fi

cd "$SERVER_ROOT"

# Source environment file if present (.env or .env.local)
for envfile in .env .env.local; do
  if [[ -f "$envfile" ]]; then
    echo "Sourcing $envfile"
    set -a
    source "./$envfile"
    set +a
    break
  fi
done

# Locate the JAR.
# Distribution layout: lib/jt400-proxy-server-<version>.jar (see dist.xml fileSet)
# Source tree: target/jt400-proxy-server-*.jar
JAR=""
if ls lib/jt400-proxy-server-*.jar >/dev/null 2>&1; then
  JAR=$(ls -1 lib/jt400-proxy-server-*.jar | head -1)
elif ls target/jt400-proxy-server-*.jar >/dev/null 2>&1; then
  JAR=$(ls -1 target/jt400-proxy-server-*.jar | head -1)
fi

if [[ -z "$JAR" ]]; then
  echo "ERROR: Could not find jt400-proxy-server*.jar" >&2
  echo "       - In a distribution package it should be in lib/" >&2
  echo "       - In a source checkout, run: mvn clean package" >&2
  exit 1
fi

# Defaults
: "${PROXY_TCP_PORT:=9400}"
: "${HIKARI_MAX_POOL_SIZE:=20}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC}"

# Ensure logs dir
mkdir -p logs

# Stop previous instance if running
if [[ -f logs/proxy.pid ]]; then
  OLD_PID=$(cat logs/proxy.pid 2>/dev/null || true)
  if [[ -n "$OLD_PID" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "Stopping previous instance (PID $OLD_PID)..."
    kill "$OLD_PID" || true
    sleep 2
  fi
  rm -f logs/proxy.pid
fi

echo "Starting jt400-proxy-server..."
echo "  JAR: $JAR"
echo "  TCP port: ${PROXY_TCP_PORT}"
echo "  Hikari max pool: ${HIKARI_MAX_POOL_SIZE}"
echo "  JAVA_OPTS: ${JAVA_OPTS}"

# Start in background
nohup java $JAVA_OPTS \
  -Dproxy.tcp.port="${PROXY_TCP_PORT}" \
  -Dhikari.maxPoolSize="${HIKARI_MAX_POOL_SIZE}" \
  -jar "$JAR" "$@" \
  > logs/proxy.out 2> logs/proxy.err &

echo $! > logs/proxy.pid
echo "Started (PID $(cat logs/proxy.pid))"
echo "Logs: logs/proxy.out  (tail -f logs/proxy.out)"
echo "Stop with: ./stop.sh  (or bin/stop.sh in a distribution)"