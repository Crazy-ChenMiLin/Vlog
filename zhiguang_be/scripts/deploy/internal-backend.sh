#!/usr/bin/env bash
set -euo pipefail

BASE="${ZHIGUANG_DEPLOY_BASE:-/home/chenmilin/zhiguang-deploy}"
REPO="$BASE/Vlog"
RUNTIME="$BASE/runtime"
SOURCE="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
BACKEND="$SOURCE/zhiguang_be"
REVISION="${GITHUB_SHA:-$(git -C "$SOURCE" rev-parse HEAD)}"
export NACOS_NAMESPACE="${NACOS_NAMESPACE:-zhiguang-prod}"
: "${NACOS_SERVER_ADDR:?Set NACOS_SERVER_ADDR}"

sudo_cmd() {
  if sudo -n true 2>/dev/null; then
    sudo "$@"
  elif [[ -n "${SUDO_PASSWORD:-}" ]]; then
    printf '%s\n' "$SUDO_PASSWORD" | sudo -S "$@"
  else
    sudo "$@"
  fi
}

compose() {
  sudo_cmd env -u COMPOSE_ENV_FILES COMPOSE_DISABLE_ENV_FILE=1 \
    NACOS_SERVER_ADDR="$NACOS_SERVER_ADDR" NACOS_USERNAME="${NACOS_USERNAME:-}" \
    NACOS_PASSWORD="${NACOS_PASSWORD:-}" NACOS_NAMESPACE="$NACOS_NAMESPACE" \
    ZHIGUANG_DEPLOY_REVISION="$REVISION" \
    docker compose --project-directory "$RUNTIME" --project-name runtime \
    -f "$BACKEND/docker-compose.prod.yml" "$@"
}

for file in "$RUNTIME/backend-secrets.yml" "$RUNTIME/java.options"; do
  if ! sudo_cmd test -s "$file"; then
    echo "Missing production startup file: $file" >&2
    exit 1
  fi
done
python3 "$BACKEND/scripts/deploy/nacos-runtime.py" check
compose config --quiet

sudo_cmd mkdir -p "$REPO"
sudo_cmd rsync -a --delete \
  --exclude '.git' \
  --exclude '.env' \
  --exclude 'zhiguang_fe/zhiguang_fe-main/node_modules' \
  --exclude 'zhiguang_fe/zhiguang_fe-main/dist' \
  "$SOURCE"/ "$REPO"/

compose build zhiguang-be
expected_image="$(sudo_cmd docker image inspect --format '{{.Id}}' zhiguang-be:internal-latest)"
compose up -d --no-build --no-deps zhiguang-be

running_image="$(sudo_cmd docker inspect --format '{{.Image}}' zhiguang-be)"
if [[ "$running_image" != "$expected_image" ]]; then
  echo "The running backend does not use the image built for $REVISION" >&2
  exit 1
fi

for _ in $(seq 1 40); do
  if curl -fsS --max-time 5 http://127.0.0.1:18080/actuator/health >/dev/null; then
    echo "Backend for $REVISION passed its health check."
    sudo_cmd docker ps --filter name=zhiguang-be --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    exit 0
  fi
  sleep 3
done

sudo_cmd docker logs --tail 120 zhiguang-be || true
exit 1
