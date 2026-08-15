#!/usr/bin/env bash
set -euo pipefail

BASE="${ZHIGUANG_DEPLOY_BASE:-/home/chenmilin/zhiguang-deploy}"
REPO="$BASE/Vlog"
RUNTIME="$BASE/runtime"
SOURCE="${GITHUB_WORKSPACE:-}"
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://100.83.242.114:9000}"
MINIO_PUBLIC_DOMAIN="${MINIO_PUBLIC_DOMAIN:-http://47.108.66.230}"
MINIO_BUCKET="${MINIO_BUCKET:-zhiguang}"

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

  if ! sudo_cmd test -f "$file"; then
    echo "Runtime env file not found: $file" >&2
    exit 1
  fi

  if sudo_cmd grep -q "^${key}=" "$file"; then
    local escaped_value="${value//\\/\\\\}"
    escaped_value="${escaped_value//&/\\&}"
    escaped_value="${escaped_value//|/\\|}"
    sudo_cmd sed -i "s|^${key}=.*|${key}=${escaped_value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" | sudo_cmd tee -a "$file" >/dev/null
  fi
}

read_env_value() {
  local file="$1"
  local key="$2"

  sudo_cmd awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$file"
}

require_env_value() {
  local key="$1"
  local value="$2"

  if [[ -z "$value" ]]; then
    echo "Required deploy environment variable is missing: $key" >&2
    exit 1
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

require_env_value "GITHUB_CLIENT_ID" "${GITHUB_CLIENT_ID:-}"
require_env_value "GITHUB_CLIENT_SECRET" "${GITHUB_CLIENT_SECRET:-}"
update_env_value "$RUNTIME/.env" "GITHUB_CLIENT_ID" "$GITHUB_CLIENT_ID"
update_env_value "$RUNTIME/.env" "GITHUB_CLIENT_SECRET" "$GITHUB_CLIENT_SECRET"

# Nacos is part of the application's bootstrap path: the container needs these
# values before it can load the hot-reloadable runtime configuration from Nacos.
# Keep the values in GitHub Secrets/Variables and materialize them only on the
# internal server's runtime .env during deployment.
require_env_value "NACOS_SERVER_ADDR" "${NACOS_SERVER_ADDR:-}"
require_env_value "NACOS_USERNAME" "${NACOS_USERNAME:-}"
require_env_value "NACOS_PASSWORD" "${NACOS_PASSWORD:-}"
update_env_value "$RUNTIME/.env" "SPRING_CONFIG_IMPORT" "${SPRING_CONFIG_IMPORT:-optional:nacos:zhiguang-runtime.yaml?refreshEnabled=true}"
update_env_value "$RUNTIME/.env" "NACOS_SERVER_ADDR" "$NACOS_SERVER_ADDR"
update_env_value "$RUNTIME/.env" "NACOS_USERNAME" "$NACOS_USERNAME"
update_env_value "$RUNTIME/.env" "NACOS_PASSWORD" "$NACOS_PASSWORD"
update_env_value "$RUNTIME/.env" "NACOS_CONFIG_GROUP" "${NACOS_CONFIG_GROUP:-ZHIGUANG_GROUP}"
update_env_value "$RUNTIME/.env" "NACOS_NAMESPACE" "${NACOS_NAMESPACE:-}"

if [[ -n "${GITHUB_REDIRECT_URI:-}" ]]; then
  update_env_value "$RUNTIME/.env" "GITHUB_REDIRECT_URI" "$GITHUB_REDIRECT_URI"
fi

MINIO_ACCESS_KEY_VALUE="${MINIO_ACCESS_KEY:-$(read_env_value "$RUNTIME/.env" "MINIO_ACCESS_KEY")}"
MINIO_SECRET_KEY_VALUE="${MINIO_SECRET_KEY:-$(read_env_value "$RUNTIME/.env" "MINIO_SECRET_KEY")}"

update_env_value "$RUNTIME/.env" "MINIO_ENDPOINT" "$MINIO_ENDPOINT"
update_env_value "$RUNTIME/.env" "MINIO_PUBLIC_DOMAIN" "$MINIO_PUBLIC_DOMAIN"
update_env_value "$RUNTIME/.env" "MINIO_BUCKET" "$MINIO_BUCKET"
update_env_value "$RUNTIME/.env" "OSS_ENDPOINT" "$MINIO_ENDPOINT"
update_env_value "$RUNTIME/.env" "OSS_PUBLIC_DOMAIN" "$MINIO_PUBLIC_DOMAIN"
update_env_value "$RUNTIME/.env" "OSS_ACCESS_KEY_ID" "$MINIO_ACCESS_KEY_VALUE"
update_env_value "$RUNTIME/.env" "OSS_ACCESS_KEY_SECRET" "$MINIO_SECRET_KEY_VALUE"
update_env_value "$RUNTIME/.env" "OSS_BUCKET" "$MINIO_BUCKET"

cat <<EOF | sudo_cmd tee "$RUNTIME/docker-compose.zhiguang-env.yml" >/dev/null
services:
  zhiguang-be:
    env_file:
      - $RUNTIME/.env
    environment:
      MINIO_ENDPOINT: \${MINIO_ENDPOINT}
      MINIO_PUBLIC_DOMAIN: \${MINIO_PUBLIC_DOMAIN}
      MINIO_ACCESS_KEY: \${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: \${MINIO_SECRET_KEY}
      MINIO_BUCKET: \${MINIO_BUCKET}
      OSS_ENDPOINT: \${OSS_ENDPOINT}
      OSS_PUBLIC_DOMAIN: \${OSS_PUBLIC_DOMAIN}
      OSS_ACCESS_KEY_ID: \${OSS_ACCESS_KEY_ID}
      OSS_ACCESS_KEY_SECRET: \${OSS_ACCESS_KEY_SECRET}
      OSS_BUCKET: \${OSS_BUCKET}
      # The runtime compose file is intentionally stable; explicitly forward
      # Nacos bootstrap settings so a later code deployment cannot drop them.
      SPRING_CONFIG_IMPORT: \${SPRING_CONFIG_IMPORT:-optional:nacos:zhiguang-runtime.yaml?refreshEnabled=true}
      SPRING_CLOUD_NACOS_SERVER_ADDR: \${NACOS_SERVER_ADDR}
      SPRING_CLOUD_NACOS_USERNAME: \${NACOS_USERNAME}
      SPRING_CLOUD_NACOS_PASSWORD: \${NACOS_PASSWORD}
      SPRING_CLOUD_NACOS_CONFIG_GROUP: \${NACOS_CONFIG_GROUP:-ZHIGUANG_GROUP}
      SPRING_CLOUD_NACOS_CONFIG_NAMESPACE: \${NACOS_NAMESPACE}
      GITHUB_CLIENT_ID: \${GITHUB_CLIENT_ID}
      GITHUB_CLIENT_SECRET: \${GITHUB_CLIENT_SECRET}
      GITHUB_REDIRECT_URI: \${GITHUB_REDIRECT_URI:-http://47.108.66.230/callback}
      CAMPUS_CLIENT_ID: \${CAMPUS_CLIENT_ID}
      CAMPUS_CLIENT_SECRET: \${CAMPUS_CLIENT_SECRET}
      CAMPUS_REDIRECT_URI: \${CAMPUS_REDIRECT_URI:-http://47.108.66.230/callback/campus}
      CAMPUS_AUTHORIZATION_ENDPOINT: \${CAMPUS_AUTHORIZATION_ENDPOINT}
      CAMPUS_TOKEN_ENDPOINT: \${CAMPUS_TOKEN_ENDPOINT}
      CAMPUS_JWKS_URI: \${CAMPUS_JWKS_URI}
      CAMPUS_SCOPES: \${CAMPUS_SCOPES:-openid profile}
EOF

sudo_cmd docker compose \
  -f "$RUNTIME/docker-compose.yml" \
  -f "$RUNTIME/docker-compose.zhiguang-env.yml" \
  --env-file "$RUNTIME/.env" \
  up -d --build

for _ in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:18080/actuator/health >/dev/null; then
    sudo_cmd docker ps --filter name=zhiguang-be --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    exit 0
  fi
  sleep 3
done

sudo_cmd docker logs --tail 120 zhiguang-be || true
exit 1
