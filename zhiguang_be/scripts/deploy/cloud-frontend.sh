#!/usr/bin/env bash
set -euo pipefail

DIST_DIR="${1:-zhiguang_fe/zhiguang_fe-main/dist}"
CLOUD_HOST="${CLOUD_HOST:-146.56.102.26}"
CLOUD_USER="${CLOUD_USER:-root}"
CLOUD_PORT="${CLOUD_PORT:-20131}"
CLOUD_SITE_DOMAIN="${CLOUD_SITE_DOMAIN:-web.chenmilin.xyz}"
CLOUD_WEB_ROOT="${CLOUD_WEB_ROOT:-/opt/1panel/www/sites/zhiguang-web/index}"
SSH_KEY_PATH="${CLOUD_SSH_KEY_PATH:-}"

if [[ ! "$CLOUD_PORT" =~ ^[0-9]+$ ]] || (( CLOUD_PORT < 1 || CLOUD_PORT > 65535 )); then
  echo "CLOUD_PORT must be a valid SSH port." >&2
  exit 1
fi
if [[ ! "$CLOUD_SITE_DOMAIN" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] ||
   [[ ! "$CLOUD_WEB_ROOT" =~ ^/[a-zA-Z0-9_./-]+$ ]] ||
   [[ "$CLOUD_WEB_ROOT" == "/" || "$CLOUD_WEB_ROOT" == *".."* ]]; then
  echo "Invalid cloud site domain or web root." >&2
  exit 1
fi

if [[ ! -d "$DIST_DIR" ]]; then
  echo "Frontend dist directory not found: $DIST_DIR" >&2
  exit 1
fi

cleanup_key() {
  if [[ -n "${TEMP_SSH_KEY:-}" && -f "$TEMP_SSH_KEY" ]]; then
    rm -f "$TEMP_SSH_KEY"
  fi
}
trap cleanup_key EXIT

if [[ -z "$SSH_KEY_PATH" && -n "${CLOUD_SSH_KEY:-}" ]]; then
  TEMP_SSH_KEY="$(mktemp)"
  printf '%b\n' "$CLOUD_SSH_KEY" | sed 's/\r$//' > "$TEMP_SSH_KEY"
  chmod 600 "$TEMP_SSH_KEY"
  SSH_KEY_PATH="$TEMP_SSH_KEY"
fi

if [[ -z "$SSH_KEY_PATH" ]]; then
  echo "CLOUD_SSH_KEY or CLOUD_SSH_KEY_PATH is required for cloud deployment." >&2
  exit 1
fi

SSH_OPTS=(-p "$CLOUD_PORT" -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)
if [[ -n "$SSH_KEY_PATH" ]]; then
  if ! ssh-keygen -y -f "$SSH_KEY_PATH" >/dev/null 2>&1; then
    echo "CLOUD_SSH_KEY is not a valid OpenSSH private key. Please paste the private key, including BEGIN/END lines, into the GitHub secret." >&2
    exit 1
  fi
  SSH_OPTS=(-i "$SSH_KEY_PATH" "${SSH_OPTS[@]}")
fi

REMOTE_TMP="/tmp/zhiguang-fe-release"

echo "Testing SSH connection to $CLOUD_USER@$CLOUD_HOST ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "echo 'Cloud SSH OK'"

echo "Uploading frontend dist to $CLOUD_USER@$CLOUD_HOST:$REMOTE_TMP ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "mkdir -p '$REMOTE_TMP'"
rsync -az --delete -e "ssh ${SSH_OPTS[*]}" "$DIST_DIR"/ "$CLOUD_USER@$CLOUD_HOST:$REMOTE_TMP"/
echo "Activating frontend release and reloading the active gateway ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "
  set -e
  mkdir -p '$CLOUD_WEB_ROOT'
  rsync -a --delay-updates --delete-delay '$REMOTE_TMP'/ '$CLOUD_WEB_ROOT'/
  openresty_container=\$(docker ps --filter 'name=1Panel-openresty' --format '{{.Names}}' | head -n 1)
  if [ -n \"\$openresty_container\" ]; then
    docker exec \"\$openresty_container\" nginx -t
    docker exec \"\$openresty_container\" nginx -s reload
  elif systemctl is-active --quiet nginx; then
    nginx -t
    systemctl reload nginx
  else
    echo 'No active OpenResty container or system Nginx service found.' >&2
    exit 1
  fi
  curl -fsS --retry 5 --retry-all-errors --retry-delay 1 \
    --resolve '$CLOUD_SITE_DOMAIN:443:127.0.0.1' \
    'https://$CLOUD_SITE_DOMAIN/' >/dev/null
  curl -fsS --retry 5 --retry-all-errors --retry-delay 1 \
    --resolve '$CLOUD_SITE_DOMAIN:443:127.0.0.1' \
    'https://$CLOUD_SITE_DOMAIN/api/v1/knowposts/feed?page=1&size=1' \
    | python3 -c 'import json,sys; data=json.load(sys.stdin); assert isinstance(data.get(\"items\"), list)'
"
