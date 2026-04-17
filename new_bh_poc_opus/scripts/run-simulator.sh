#!/usr/bin/env bash
# Start the sensor simulator (Kafka producer).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KAFKA_BOOTSTRAP:=localhost:9092}"
: "${SOURCE_TOPIC:=sensor-events}"
: "${STREAM_SENSORS:=2500}"
: "${BATCHED_SENSORS:=500}"

java -cp target/bh-asset-monitoring-poc-all.jar \
    -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
    com.bh.poc.simulator.SensorSimulator
