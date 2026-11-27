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
release_url="https://nodejs.org/dist/$version"
mirror_url="https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/$version"

cd "$download_dir"
curl_options=(--fail --silent --show-error --location --connect-timeout 10)
curl "${curl_options[@]}" --max-time 20 --retry 2 --output SHASUMS256.txt "$release_url/SHASUMS256.txt"
awk -v archive="$archive" '$2 == archive {print}' SHASUMS256.txt > node.sha256
test -s node.sha256

if ! curl "${curl_options[@]}" --max-time 45 --output "$archive" "$mirror_url/$archive" ||
   ! sha256sum --check node.sha256; then
  curl "${curl_options[@]}" --max-time 90 --output "$archive" "$release_url/$archive"
  sha256sum --check node.sha256
fi
tar -xJf "$archive"

bin="$download_dir/${archive%.tar.xz}/bin"
"$bin/node" --version
printf '%s\n' "$bin" >> "${GITHUB_PATH:?Set GITHUB_PATH}"
