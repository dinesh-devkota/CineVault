#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: run_commercial_stage.sh STAGE LOG COMMAND [ARGUMENT ...]" >&2
  exit 2
fi

stage=$1
log=$2
shift 2
umask 077
printf '\n--- %s ---\n' "$stage" >> "$log"

if "$@" >> "$log" 2>&1; then
  printf '%s succeeded.\n' "$stage"
else
  status=$?
  printf '::error::%s failed (exit %d). Private output is withheld from public logs.\n' "$stage" "$status" >&2
  exit "$status"
fi
