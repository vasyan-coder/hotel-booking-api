#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for service in gateway booking-service hotel-service discovery-server; do
  file=".run/$service.pid"
  if [[ -f "$file" ]]; then
    pid=$(cat "$file")
    if ps -p "$pid" -o args= 2>/dev/null | grep -Fq "$service/target/$service-1.0.0.jar"; then
      kill "$pid" 2>/dev/null || true
      for ((attempt=0; attempt<100; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 0.2
      done
      if kill -0 "$pid" 2>/dev/null; then
        echo "$service is still stopping; keeping its PID file" >&2
        exit 1
      fi
      echo "Stopped $service"
    fi
    rm -f "$file"
  fi
done
