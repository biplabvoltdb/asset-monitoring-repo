#!/usr/bin/env bash
# Tail the alert Kafka topic.
set -euo pipefail
: "${KAFKA_BOOTSTRAP:=localhost:9092}"

if command -v kafka-console-consumer.sh >/dev/null 2>&1; then
    kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic alert --from-beginning
else
    kafka-console-consumer --bootstrap-server "$KAFKA_BOOTSTRAP" --topic alert --from-beginning
fi
