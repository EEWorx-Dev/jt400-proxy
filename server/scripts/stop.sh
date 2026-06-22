#!/usr/bin/env bash
set -euo pipefail

# jt400-proxy-server stop wrapper (Unix / macOS / Linux)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$(basename "$SCRIPT_DIR")" == "scripts" || "$(basename "$SCRIPT_DIR")" == "bin" ]]; then
  SERVER_ROOT="$(dirname "$SCRIPT_DIR")"
else
  SERVER_ROOT="$SCRIPT_DIR"
fi

cd "$SERVER_ROOT"

# Accept (and ignore) optional env file argument for symmetry with start/run
# e.g. stop.sh, stop.sh prod.env, stop.sh --env-file prod.env
if [[ $# -gt 0 ]]; then
  case "$1" in
    --env-file|--env)
      if [[ $# -ge 2 ]]; then
        shift 2
      else
        shift
      fi
      ;;
    *)
      if [[ -f "$1" ]]; then
        shift
      fi
      ;;
  esac
fi

if [[ -f logs/proxy.pid ]]; then
  PID=$(cat logs/proxy.pid)
  if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping jt400-proxy-server (PID $PID)..."
    kill "$PID" || true
    sleep 2
    if kill -0 "$PID" 2>/dev/null; then
      echo "Still running, sending SIGKILL..."
      kill -9 "$PID" || true
    fi
  else
    echo "Process with PID $PID is not running."
  fi
  rm -f logs/proxy.pid
  echo "Stopped."
else
  echo "No PID file found (logs/proxy.pid)."
  # Fallback
  pkill -f "jt400-proxy-server" 2>/dev/null && echo "Stopped matching process." || echo "No matching process found."
fi
