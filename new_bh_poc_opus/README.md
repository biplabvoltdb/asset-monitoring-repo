# BH Real-Time Asset Monitoring PoC (VoltSP + VoltDB + Kafka)

End-to-end PoC implementing the rule-based alerting and hierarchical roll-up requirements.

```
Kafka(sensor-events) ── VoltSP pipeline ─────────────── Kafka(alert)
                        │  Java processor: AlertEvaluationProcessor
                        │     ├── GetSensorTag        (metadata / thresholds)
                        │     ├── AppendWindowSample  (Rule 3 state)
                        │     ├── EvaluateWindow      (Rule 3)
                        │     ├── GetActiveAlertByTag (Rule 4/5)
                        │     ├── EscalateAlert       (Rule 4)
                        │     ├── CloseAlert          (Rule 5)
                        │     └── UpsertAlert         (persist to ALERT + rollup)
                        └── all VoltDB calls go through the "voltdb"
                            voltdb-client resource (see pipeline-config.yaml)
```

## Modules

| Path | Purpose |
|---|---|
| `src/main/resources/ddl.sql` | Tables + partition + procedure declarations |
| `src/main/java/com/bh/poc/procedures/` | VoltDB stored procedures |
| `src/main/java/com/bh/poc/pipeline/AssetMonitoringPipeline.java` | VoltSP pipeline (Java DSL) |
| `src/main/java/com/bh/poc/processors/AlertEvaluationProcessor.java` | Rule engine; persists alerts via VoltDB client |
| `src/main/java/com/bh/poc/pipeline/VoltClientHolder.java` | Process-wide VoltDB client matching the `voltdb` resource |
| `src/main/java/com/bh/poc/setup/AssetLoader.java` | Seeds ASSET + SENSOR_TAG tables |
| `src/main/java/com/bh/poc/simulator/SensorSimulator.java` | 2500 streaming + 500 batched sensors to Kafka |
| `config/pipeline-config.yaml` | VoltSP runtime config (voltdb-client resource + Kafka) |
| `scripts/` | create-topics / deploy-schema / load-assets / start-pipeline / run-simulator / consume-alerts |

## Rules implemented

| Rule | Trigger | Severity | Impl |
|---|---|---|---|
| R1 | Temp > threshold (first breach) | High | `AlertEvaluationProcessor.evaluateRules` + `UpsertAlert` |
| R2 | Pressure > threshold then latest temp > high | High | SENSOR_WINDOW cross-tag lookup |
| R3 | ≥30% of samples in 10-min window breach both | Medium | `AppendWindowSample` + `EvaluateWindow` |
| R4 | Continued breach of active alert | Critical (escalated) | `GetActiveAlertByTag` + `EscalateAlert` |
| R5 | Value recovers below threshold | (Closed) | `CloseAlert` |

## Real-time analytics

- `HierarchyRollup(rootNodeId)` - total/active alerts across descendants
- `TopContributors(parentNodeId, X)` - top-X child nodes by total alerts

## Run order

```
mvn package
./scripts/create-topics.sh
./scripts/deploy-schema.sh      # uploads procedure classes + ddl.sql
./scripts/load-assets.sh         # seeds ASSET + SENSOR_TAG
./scripts/start-pipeline.sh      # VoltSP pipeline
./scripts/run-simulator.sh       # start sensor workload
./scripts/consume-alerts.sh      # watch alerts flow out to Kafka
```

## Workload profile

- 2500 streaming sensors @ 1 Hz -> ~2500 eps
- 500 batched sensors, 120 samples every 2 min -> 60k samples / 2 min
- ~5% of values breach threshold to exercise all five rules

## Notes on voltdb-client resource usage

The pipeline declares the VoltDB endpoint once as a `voltdb-client` resource named
`voltdb` in `config/pipeline-config.yaml`. The Java processor opens a matching
VoltDB client using the same `voltdb.servers` / `voltdb.user` / `voltdb.password`
runtime-config values (via `VoltClientHolder`), so all VoltDB interactions from
the processor - metadata / threshold checks, window state, alert writes - flow
through the same resource definition. If you later swap the pipeline to use
`voltdb-procedure` sinks for any step, simply reference `voltClientResource:
"voltdb"` - no code change.
