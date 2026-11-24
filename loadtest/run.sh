#!/usr/bin/env sh
# Runs the Howlers load test with the official k6 image against the Docker stack on http://localhost:8088.
# Usage (from the repo root):  sh loadtest/run.sh                    full run, about 4.5 minutes
#                              SCALE=0.2 sh loadtest/run.sh          one-minute smoke run
#                              SCRIPT=peak.js sh loadtest/run.sh     90-second read-only capacity probe
# Results land in loadtest/results/ (latest.md, plus timestamped .md and .json files).
set -eu
cd "$(dirname "$0")"
mkdir -p results
DOCKER="${DOCKER:-docker}"
MSYS_NO_PATHCONV=1 "$DOCKER" run --rm \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="${BASE_URL:-http://host.docker.internal:8088}" \
  -e SCALE="${SCALE:-1}" -e RATES="${RATES:-50,150,300}" \
  -v "$(pwd -W 2>/dev/null || pwd):/work" -w /work \
  grafana/k6:0.54.0 run --quiet "${SCRIPT:-howlers.js}"
