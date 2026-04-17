#!/usr/bin/env bash
# Seed the ASSET + SENSOR_TAG tables so the processor can resolve metadata.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VOLTDB_HOST:=localhost:21212}"
: "${STREAM_SENSORS:=2500}"
: "${BATCHED_SENSORS:=500}"

java -cp "target/bh-asset-monitoring-poc-all.jar:$(ls ~/.m2/repository/org/voltdb/voltdbclient/*/voltdbclient-*.jar | tail -1)" \
    com.bh.poc.setup.AssetLoader "$VOLTDB_HOST" "$STREAM_SENSORS" "$BATCHED_SENSORS"
