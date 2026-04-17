#!/usr/bin/env bash
# Create the Kafka topics used by the PoC.
set -euo pipefail
: "${KAFKA_BOOTSTRAP:=localhost:9092}"
: "${KAFKA_BIN:=$(dirname "$(command -v kafka-topics.sh 2>/dev/null || echo .)" )}"

run_kafka_topics() {
    if command -v kafka-topics.sh >/dev/null 2>&1; then
        kafka-topics.sh "$@"
    else
        kafka-topics "$@"
    fi
}

run_kafka_topics --bootstrap-server "$KAFKA_BOOTSTRAP" --create --if-not-exists \
    --topic sensor-events --partitions 12 --replication-factor 1
run_kafka_topics --bootstrap-server "$KAFKA_BOOTSTRAP" --create --if-not-exists \
    --topic alert --partitions 6 --replication-factor 1

echo "Topics sensor-events + alert ready on $KAFKA_BOOTSTRAP"
