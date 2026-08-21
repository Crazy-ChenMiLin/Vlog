#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${1:-}"
DATA_ID="${NACOS_DATA_ID:-zhiguang-runtime.yaml}"
GROUP="${NACOS_CONFIG_GROUP:-ZHIGUANG_GROUP}"
SERVER_ADDR="${NACOS_SERVER_ADDR:-}"
# Nacos's built-in public namespace has an empty namespace ID. Do not send the
# display name "public", otherwise the producer and the application can target
# different namespaces.
NAMESPACE_ID="${NACOS_NAMESPACE:-}"

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

# Nacos 3 uses a separate administrative configuration API. Keep the access
# token only in this shell process so it never appears in Action logs.
echo "Authenticating with Nacos 3 at $SERVER_ADDR"
LOGIN_RESPONSE="$(curl --fail --silent --show-error \
  --request POST "$SERVER_ADDR/nacos/v3/auth/user/login" \
  --data-urlencode "username=$NACOS_USERNAME" \
  --data-urlencode "password=$NACOS_PASSWORD")"
ACCESS_TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"

if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "Nacos login succeeded without an access token" >&2
  exit 1
fi

echo "Publishing $DATA_ID to Nacos group $GROUP"
PUBLISH_RESPONSE="$(curl --fail --silent --show-error \
  --request POST "$SERVER_ADDR/nacos/v3/admin/cs/config" \
  --header "accessToken: $ACCESS_TOKEN" \
  --data-urlencode "namespaceId=$NAMESPACE_ID" \
  --data-urlencode "dataId=$DATA_ID" \
  --data-urlencode "groupName=$GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content@$CONFIG_FILE")"

if [[ "$PUBLISH_RESPONSE" != *'"code":0'* ]]; then
  echo "Nacos rejected configuration publication for $DATA_ID in $GROUP" >&2
  exit 1
fi

echo "Published Nacos configuration: $DATA_ID ($GROUP)"
