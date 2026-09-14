#!/usr/bin/env bash
set -euo pipefail

readonly model_url="https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin"
readonly expected_size="147951465"
readonly expected_sha="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
readonly output="${1:-app/src/main/assets/models/ggml-base.bin}"
readonly partial="${output}.part"

mkdir -p "$(dirname "$output")"

verify_model() {
  [[ -f "$1" ]] &&
    [[ "$(wc -c < "$1" | tr -d ' ')" == "$expected_size" ]] &&
    echo "$expected_sha  $1" | sha256sum --check --status
}

if [[ -f "$output" ]]; then
  if verify_model "$output"; then
    echo "Modelo Whisper verificado: $output"
    exit 0
  fi
  echo "El modelo existente no coincide con el tamaño o SHA-256 esperado." >&2
  exit 1
fi

trap 'rm -f "$partial"' EXIT
curl --fail --location --retry 5 --retry-all-errors "$model_url" --output "$partial"
verify_model "$partial"
mv "$partial" "$output"
trap - EXIT

echo "Modelo Whisper descargado y verificado: $output"
