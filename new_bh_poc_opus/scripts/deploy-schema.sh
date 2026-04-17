#!/usr/bin/env bash
# Deploy DDL + stored-procedure classes into VoltDB.
# Prereqs: mvn package has produced target/bh-asset-monitoring-poc-all.jar
set -euo pipefail
cd "$(dirname "$0")/.."

: "${VOLTDB_HOST:=localhost}"
JAR=target/bh-asset-monitoring-poc-all.jar

if [[ ! -f "$JAR" ]]; then
    echo "Building project..."
    mvn -q -DskipTests package
fi

# Load procedure classes, then DDL
sqlcmd --servers="$VOLTDB_HOST" <<EOF
LOAD CLASSES $JAR;
FILE src/main/resources/ddl.sql;
EOF

echo "DDL + procedures loaded into VoltDB at $VOLTDB_HOST"
