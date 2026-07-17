#!/usr/bin/env bash
set -euo pipefail

BASE="${ZHIGUANG_DEPLOY_BASE:-/home/chenmilin/zhiguang-deploy}"
REPO="$BASE/Vlog"
RUNTIME="$BASE/runtime"
SOURCE="${GITHUB_WORKSPACE:-}"

if [[ -z "$SOURCE" ]]; then
  SOURCE="$(git rev-parse --show-toplevel)"
fi

if [[ ! -d "$SOURCE/zhiguang_be" ]]; then
  echo "Cannot find backend source at $SOURCE/zhiguang_be" >&2
  exit 1
fi

sudo_cmd() {
  if sudo -n true 2>/dev/null; then
    sudo "$@"
  elif [[ -n "${SUDO_PASSWORD:-}" ]]; then
    printf '%s\n' "$SUDO_PASSWORD" | sudo -S "$@"
  else
    sudo "$@"
  fi
}

update_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"

  if [[ ! -f "$file" ]]; then
    echo "Runtime env file not found: $file" >&2
    exit 1
  fi

  if grep -q "^${key}=" "$file"; then
    local escaped_value="${value//\\/\\\\}"
    escaped_value="${escaped_value//&/\\&}"
    escaped_value="${escaped_value//|/\\|}"
    sudo_cmd sed -i "s|^${key}=.*|${key}=${escaped_value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" | sudo_cmd tee -a "$file" >/dev/null
  fi
}

sudo_cmd mkdir -p "$REPO"
sudo_cmd rsync -a --delete \
  --exclude '.git' \
  --exclude 'zhiguang_fe/zhiguang_fe-main/node_modules' \
  --exclude 'zhiguang_fe/zhiguang_fe-main/dist' \
  "$SOURCE"/ "$REPO"/

if [[ -n "${SMTP:-}" ]]; then
  update_env_value "$RUNTIME/.env" "AUTH_MAIL_ENABLED" "true"
  update_env_value "$RUNTIME/.env" "SPRING_MAIL_PASSWORD" "$SMTP"
fi

sudo_cmd docker compose -f "$RUNTIME/docker-compose.yml" --env-file "$RUNTIME/.env" up -d --build

for _ in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:18080/actuator/health >/dev/null; then
    sudo_cmd docker ps --filter name=zhiguang-be --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    exit 0
  fi
  sleep 3
done

sudo_cmd docker logs --tail 120 zhiguang-be || true
exit 1
