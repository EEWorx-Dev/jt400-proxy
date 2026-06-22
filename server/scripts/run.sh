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

# Default location for env file (in conf/ for cleaner distributions)
DEFAULT_JT400_ENV_FILE="conf/.env"

# Determine JT400_ENV_FILE:
# - If supplied as parameter using explicit '--env' or '--env-file', use that value.
# - Otherwise default to DEFAULT_JT400_ENV_FILE.
# Bare first argument that is an existing file is also accepted as override.
JT400_ENV_FILE=""
if [[ $# -gt 0 ]]; then
  case "$1" in
    --env-file|--env)
      if [[ $# -ge 2 ]]; then
        JT400_ENV_FILE="$2"
        shift 2
      fi
      ;;
    *)
      if [[ -f "$1" ]]; then
        JT400_ENV_FILE="$1"
        shift
      fi
      ;;
  esac
fi

if [[ -z "$JT400_ENV_FILE" ]]; then
  JT400_ENV_FILE="$DEFAULT_JT400_ENV_FILE"
fi

# Source environment file
SOURCED=""
if [[ -f "$JT400_ENV_FILE" ]]; then
  echo "Sourcing $JT400_ENV_FILE"
  set -a
  source "$JT400_ENV_FILE"
  set +a
  SOURCED=1
elif [[ "$JT400_ENV_FILE" != "$DEFAULT_JT400_ENV_FILE" ]]; then
  # Only warn when an explicit file (via arg or --env*) was requested but missing
  echo "WARNING: Specified env file not found: $JT400_ENV_FILE" >&2
fi

# If using the default and it was not found, fall back to traditional root-level
# locations. This keeps source tree usage (server/.env) and previous docs working.
if [[ -z "$SOURCED" && "$JT400_ENV_FILE" == "$DEFAULT_JT400_ENV_FILE" ]]; then
  for envfile in .env .env.local; do
    if [[ -f "$envfile" ]]; then
      set -a
      source "./$envfile"
      set +a
      break
    fi
  done
fi

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
