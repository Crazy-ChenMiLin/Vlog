#!/usr/bin/env bash
set -euo pipefail

DIST_DIR="${1:-zhiguang_fe/zhiguang_fe-main/dist}"
CLOUD_HOST="${CLOUD_HOST:-47.108.66.230}"
CLOUD_USER="${CLOUD_USER:-root}"
CLOUD_WEB_ROOT="${CLOUD_WEB_ROOT:-/var/www/zhiguang-fe}"
SSH_KEY_PATH="${CLOUD_SSH_KEY_PATH:-}"

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
  printf '%s\n' "$CLOUD_SSH_KEY" | sed 's/\r$//' > "$TEMP_SSH_KEY"
  chmod 600 "$TEMP_SSH_KEY"
  SSH_KEY_PATH="$TEMP_SSH_KEY"
fi

if [[ -z "$SSH_KEY_PATH" ]]; then
  echo "CLOUD_SSH_KEY or CLOUD_SSH_KEY_PATH is required for cloud deployment." >&2
  exit 1
fi

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)
if [[ -n "$SSH_KEY_PATH" ]]; then
  SSH_OPTS=(-i "$SSH_KEY_PATH" "${SSH_OPTS[@]}")
fi

REMOTE_TMP="/tmp/zhiguang-fe-release"

echo "Testing SSH connection to $CLOUD_USER@$CLOUD_HOST ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "echo 'Cloud SSH OK'"

echo "Uploading frontend dist to $CLOUD_USER@$CLOUD_HOST:$REMOTE_TMP ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "rm -rf '$REMOTE_TMP' && mkdir -p '$REMOTE_TMP'"
rsync -az --delete -e "ssh ${SSH_OPTS[*]}" "$DIST_DIR"/ "$CLOUD_USER@$CLOUD_HOST:$REMOTE_TMP"/
echo "Activating frontend release and reloading nginx ..."
ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "
  set -e
  mkdir -p '$CLOUD_WEB_ROOT'
  rsync -a --delete '$REMOTE_TMP'/ '$CLOUD_WEB_ROOT'/
  nginx -t
  systemctl reload nginx
  curl -fsS http://127.0.0.1/ >/dev/null
  curl -fsS 'http://127.0.0.1/api/v1/knowposts/feed?page=1&size=1' >/dev/null
"
