#!/usr/bin/env bash

set -euo pipefail

: "${MOCK_CURL_RESPONSES_FILE:?MOCK_CURL_RESPONSES_FILE is required.}"
: "${MOCK_CURL_STATE_FILE:?MOCK_CURL_STATE_FILE is required.}"

if [[ -n "${MOCK_CURL_ARGUMENTS_FILE:-}" ]]; then
  printf '%q ' "$@" >"$MOCK_CURL_ARGUMENTS_FILE"
  printf '\n' >>"$MOCK_CURL_ARGUMENTS_FILE"
fi

output_file=
previous=
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then
    output_file=$argument
  fi
  previous=$argument
done

if [[ -f "$MOCK_CURL_STATE_FILE" ]]; then
  invocation=$(<"$MOCK_CURL_STATE_FILE")
else
  invocation=0
fi

invocation=$((invocation + 1))
printf '%s\n' "$invocation" >"$MOCK_CURL_STATE_FILE"

response=$(sed -n "${invocation}p" "$MOCK_CURL_RESPONSES_FILE")
if [[ -z "$response" ]]; then
  response=$(tail -n 1 "$MOCK_CURL_RESPONSES_FILE")
fi

case "$response" in
  network)
    echo "curl: simulated network failure." >&2
    exit 7
    ;;
  curl-[0-9] | curl-[0-9][0-9])
    exit "${response#curl-}"
    ;;
  [0-9][0-9][0-9])
    if [[ -n "$output_file" ]]; then
      if [[ "$response" == "200" && -n "${MOCK_CURL_BODY_FILE:-}" ]]; then
        cp "$MOCK_CURL_BODY_FILE" "$output_file"
      else
        : >"$output_file"
      fi
    fi
    printf '%s' "$response"
    ;;
  *)
    echo "mock-curl: unsupported response ${response}." >&2
    exit 2
    ;;
esac
