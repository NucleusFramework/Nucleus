#!/usr/bin/env bash
# apt-install.sh — Install Ubuntu packages on a CI runner without letting a dead mirror hang the job.
#
# Usage: apt-install.sh <package>...
#
# The runner's apt mirror (azure.archive.ubuntu.com) sometimes stops answering, and apt waits
# 120 s on each stalled connection, so one `apt-get update` once ran out a 60-minute job. Here a
# connection that sends nothing for 30 s is dropped and the file retried, up to three times.
# The index is still refreshed first: a week-old runner image lists versions the archive has
# since removed, which `install` would 404 on.

set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: apt-install.sh <package>..." >&2
  exit 2
fi

APT_OPTS=(
  -o Acquire::Retries=3
  -o Acquire::http::Timeout=30
  -o Acquire::https::Timeout=30
)

sudo apt-get "${APT_OPTS[@]}" update
sudo apt-get "${APT_OPTS[@]}" install -y "$@"
