#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-env.sh
set -a
source .env
set +a
export EUREKA_URL="${EUREKA_URL:-http://localhost:${DISCOVERY_PORT:-8761}/eureka/}"
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p .run
for service in discovery-server hotel-service booking-service gateway; do
  if [[ ! -f "$service/target/$service-1.0.0.jar" ]]; then
    ./mvnw -B -DskipTests package
    break
  fi
done
started=()
cleanup_on_error() {
  for service in "${started[@]}"; do
    kill "$(cat ".run/$service.pid")" 2>/dev/null || true
    rm -f ".run/$service.pid"
  done
}
trap cleanup_on_error ERR
trap 'cleanup_on_error; exit 130' INT TERM
for item in "discovery-server:${DISCOVERY_PORT:-8761}" "hotel-service:${HOTEL_PORT:-8082}" "booking-service:${BOOKING_PORT:-8081}" "gateway:${GATEWAY_PORT:-8080}"; do
  service=${item%:*}
  port=${item#*:}
  if [[ -f ".run/$service.pid" ]] && ps -p "$(cat ".run/$service.pid")" -o args= 2>/dev/null | grep -Fq "$service/target/$service-1.0.0.jar"; then
    echo "$service is already running"
    continue
  fi
  if curl --connect-timeout 1 --max-time 2 -fsS "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "Port $port is occupied; stop that process before starting $service" >&2
    false
  fi
  nohup "$JAVA_CMD" -Xms64m -Xmx256m -Dserver.port="$port" -jar "$service/target/$service-1.0.0.jar" > ".run/$service.log" 2>&1 &
  echo $! > ".run/$service.pid"
  started+=("$service")
  ready=false
  for ((attempt=0; attempt<120; attempt++)); do
    if curl --connect-timeout 1 --max-time 2 -fsS "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
      ready=true
      break
    fi
    if ! kill -0 "$(cat ".run/$service.pid")" 2>/dev/null; then
      tail -50 ".run/$service.log"
      false
    fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then
    echo "$service did not become healthy; inspect .run/$service.log" >&2
    false
  fi
  echo "$service is ready on port $port"
done
echo "Gateway: http://localhost:${GATEWAY_PORT:-8080}. Discovery convergence may take up to 30 seconds."
