#!/usr/bin/env bash
set -euo pipefail

version=v20.20.2
case "$(uname -m)" in
  x86_64) architecture=x64 ;;
  aarch64|arm64) architecture=arm64 ;;
  *) echo "Unsupported runner architecture: $(uname -m)" >&2; exit 1 ;;
esac

download_dir="$(mktemp -d "${RUNNER_TEMP:?Set RUNNER_TEMP}/zhiguang-node.XXXXXX")"
archive="node-$version-linux-$architecture.tar.xz"
url="https://nodejs.org/dist/$version"

cd "$download_dir"
curl_options=(--fail --silent --show-error --location --connect-timeout 10 --max-time 60 --retry 2)
curl "${curl_options[@]}" --output SHASUMS256.txt "$url/SHASUMS256.txt"
curl "${curl_options[@]}" --output "$archive" "$url/$archive"
awk -v archive="$archive" '$2 == archive {print}' SHASUMS256.txt | sha256sum --check -
tar -xJf "$archive"

bin="$download_dir/${archive%.tar.xz}/bin"
"$bin/node" --version
printf '%s\n' "$bin" >> "${GITHUB_PATH:?Set GITHUB_PATH}"
