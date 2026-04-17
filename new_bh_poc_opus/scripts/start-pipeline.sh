#!/usr/bin/env bash
# Launch the VoltSP pipeline locally using the voltsp CLI.
# Prereqs:
#   - voltsp on PATH
#   - VOLTDB_LICENSE or -l license.xml
#   - mvn package
set -euo pipefail
cd "$(dirname "$0")/.."

export CP="target/bh-asset-monitoring-poc-all.jar"
: "${LICENSE:=${VOLTDB_LICENSE:-$HOME/license.xml}}"

voltsp ${LICENSE:+-l "$LICENSE"} \
    --config config/pipeline-config.yaml \
    com.bh.poc.pipeline.AssetMonitoringPipeline
