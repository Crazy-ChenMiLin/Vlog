#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${1:-}"
DATA_ID="${NACOS_DATA_ID:-zhiguang-runtime.yaml}"
GROUP="${NACOS_CONFIG_GROUP:-ZHIGUANG_GROUP}"
SERVER_ADDR="${NACOS_SERVER_ADDR:-}"

require_env_value() {
  local key="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "Required Nacos environment variable is missing: $key" >&2
    exit 1
  fi
}

require_env_value "NACOS_SERVER_ADDR" "$SERVER_ADDR"
require_env_value "NACOS_USERNAME" "${NACOS_USERNAME:-}"
require_env_value "NACOS_PASSWORD" "${NACOS_PASSWORD:-}"

if [[ -z "$CONFIG_FILE" || ! -f "$CONFIG_FILE" ]]; then
  echo "Nacos configuration file not found: $CONFIG_FILE" >&2
  exit 1
fi

if [[ "$SERVER_ADDR" != http://* && "$SERVER_ADDR" != https://* ]]; then
  SERVER_ADDR="http://$SERVER_ADDR"
fi
SERVER_ADDR="${SERVER_ADDR%/}"

# Authenticate once, then publish the reviewed repository YAML. The token is
# intentionally kept in a shell variable and never printed to Action logs.
LOGIN_RESPONSE="$(curl --fail --silent --show-error \
  --request POST "$SERVER_ADDR/nacos/v1/auth/login" \
  --data-urlencode "username=$NACOS_USERNAME" \
  --data-urlencode "password=$NACOS_PASSWORD")"
ACCESS_TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"

if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "Nacos login succeeded without an access token" >&2
  exit 1
fi

PUBLISH_RESPONSE="$(curl --fail --silent --show-error \
  --request POST "$SERVER_ADDR/nacos/v1/cs/configs" \
  --data-urlencode "accessToken=$ACCESS_TOKEN" \
  --data-urlencode "dataId=$DATA_ID" \
  --data-urlencode "group=$GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content@$CONFIG_FILE")"

if [[ "$PUBLISH_RESPONSE" != "true" ]]; then
  echo "Nacos rejected configuration publication for $DATA_ID in $GROUP" >&2
  exit 1
fi

echo "Published Nacos configuration: $DATA_ID ($GROUP)"
