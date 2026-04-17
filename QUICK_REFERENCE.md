# Asset Monitoring Solution - Quick Reference Card

## Project Locations

```
/Users/biplab/Download/
├── asset-monitoring/              # VoltDB backend
├── asset-monitoring-voltsp/       # VoltSP streaming pipelines
├── INTEGRATION_GUIDE.md           # Complete deployment guide
├── PROJECT_SUMMARY.md             # Full project documentation
└── QUICK_REFERENCE.md             # This file
```

## Build Commands

```bash
# VoltDB
cd /Users/biplab/Download/asset-monitoring
mvn clean package -DskipTests

# VoltSP
cd /Users/biplab/Download/asset-monitoring-voltsp
mvn clean package
```

## Deployment Commands

### Local (Bare Metal)

```bash
# 1. Start VoltDB
cd asset-monitoring
voltdb init && voltdb start

# 2. Deploy schema
java -jar target/asset-monitoring-1.0.jar localhost 21211

# 3. Create Kafka topics
for topic in sensor-data alerts-high alerts-medium alerts-critical; do
  kafka-topics --create --topic $topic --bootstrap-server localhost:9092
done

# 4. Start VoltSP pipelines
cd ../asset-monitoring-voltsp
export VOLTDB_LICENSE=/path/to/license.xml
./deploy.sh  # Select option 2
```

### Kubernetes

```bash
# Build both projects first
cd asset-monitoring && mvn package -DskipTests && cd ..
cd asset-monitoring-voltsp && mvn package && cd ..

# Deploy VoltSP pipelines
cd asset-monitoring-voltsp
export VOLTDB_LICENSE=/path/to/license.xml
./deploy.sh  # Select option 1
```

## Testing Commands

### Send Test Data

```bash
# Normal (no alert)
echo '{"tags":[{"name":"Asset1.Compressor_Sensor_01","results":[{"values":[['$(date +%s000)',45.0,1,20.0]]}]}]}' | \
  kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# High temp (Rule 1)
echo '{"tags":[{"name":"Asset1.Compressor_Sensor_01","results":[{"values":[['$(date +%s000)',55.0,1,20.0]]}]}]}' | \
  kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Sequential (Rule 2)
echo '{"tags":[{"name":"Asset1.Compressor_Sensor_01","results":[{"values":[['$(date +%s000)',55.0,1,35.0]]}]}]}' | \
  kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Escalation (Rule 4)
echo '{"tags":[{"name":"Asset1.Compressor_Sensor_01","results":[{"values":[['$(date +%s000)',60.0,1,40.0]]}]}]}' | \
  kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Recovery (Rule 5)
echo '{"tags":[{"name":"Asset1.Compressor_Sensor_01","results":[{"values":[['$(date +%s000)',40.0,1,20.0]]}]}]}' | \
  kafka-console-producer --broker-list localhost:9092 --topic sensor-data
```

### Monitor Alerts

```bash
# Kafka
kafka-console-consumer --bootstrap-server localhost:9092 --topic alerts-high --from-beginning
kafka-console-consumer --bootstrap-server localhost:9092 --topic alerts-critical --from-beginning

# VoltDB
sqlcmd
SELECT * FROM ALERT WHERE status = 'Active';
SELECT * FROM HIERARCHICAL_METRICS;
```

## Key SQL Queries

```sql
-- Active alerts
SELECT alert_id, tag_name, title, severity, breach_count
FROM ALERT WHERE status = 'Active';

-- Alerts by asset
EXEC GetAlertsByNode 'asset-001', true;

-- Hierarchical metrics
SELECT node_id, total_alerts, total_active_alerts
FROM HIERARCHICAL_METRICS
ORDER BY total_alerts DESC;

-- Top 5 contributors
EXEC GetTopContributors 'fleet-001', 5, false;

-- Recent sensor readings
SELECT tag_name, timestamp, temperature, pressure
FROM SENSOR_READING
WHERE timestamp > SINCE_EPOCH(MINUTE, NOW - 10)
ORDER BY timestamp DESC;

-- Rolling window aggregates
SELECT node_id, window_start, combined_breach_count
FROM ROLLING_WINDOW_AGG
ORDER BY window_start DESC;

-- Procedure stats
SELECT PROCEDURE, INVOCATIONS, AVG_EXECUTION_TIME
FROM PROCEDURE_STATISTICS
WHERE PROCEDURE LIKE '%Sensor%' OR PROCEDURE LIKE '%Alert%';
```

## VoltDB Procedures

| Procedure | Parameters | Purpose |
|-----------|-----------|---------|
| ProcessSensorReading | tag, ts, temp, press, qual | Rules 1,2,4,5 |
| AnalyzeRollingWindow | nodeId, start, end | Rule 3 |
| ComputeHierarchicalMetrics | (none) | Rule 6 |
| GetTopContributors | parentId, topN, byActive | Rule 7 |
| UpsertAsset | nodeId, name, type, parent, tags | Asset mgmt |
| RegisterTagMapping | tagName, nodeId | Tag setup |
| GetAlertsByNode | nodeId, activeOnly | Query alerts |
| GetAssetHierarchy | (none) | Query hierarchy |

## Configuration Files

### VoltDB
- Schema: `asset-monitoring/src/main/resources/ddl.sql`
- Cleanup: `asset-monitoring/src/main/resources/remove_db.sql`

### VoltSP
- Main pipeline: `asset-monitoring-voltsp/configuration.yaml`
- Rolling window: `asset-monitoring-voltsp/configuration-rolling-window.yaml`
- Secrets: `asset-monitoring-voltsp/configurationSecure.yaml`
- Helm (main): `asset-monitoring-voltsp/helm/values-main-pipeline.yaml`
- Helm (window): `asset-monitoring-voltsp/helm/values-rolling-window.yaml`

## Environment Variables

```bash
# VoltDB
export VOLTDB_LICENSE=/path/to/license.xml

# Kafka
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# VoltDB connection
export VOLTDB_HOST=localhost
export VOLTDB_PORT=21211

# Pipeline tuning
export PIPELINE_PARALLELISM=4
```

## Kubernetes Commands

```bash
# Check status
kubectl get pods -l app.kubernetes.io/instance=asset-monitoring
kubectl get pods -l app.kubernetes.io/instance=rolling-window

# View logs
kubectl logs -f deployment/asset-monitoring
kubectl logs -f deployment/rolling-window

# Scale
kubectl scale deployment asset-monitoring --replicas=5

# Delete
helm uninstall asset-monitoring
helm uninstall rolling-window
```

## Troubleshooting

### No alerts generated
```bash
# Check Kafka input
kafka-console-consumer --topic sensor-data --from-beginning

# Check tag mapping
sqlcmd
SELECT * FROM TAG_ASSET_MAPPING;

# Test procedure directly
EXEC ProcessSensorReading 'Asset1.Compressor_Sensor_01', 1677840000000, 55.0, 35.0, 1;
```

### Pipeline not connecting to VoltDB
```bash
# Test connection
telnet $VOLTDB_HOST $VOLTDB_PORT

# Check VoltDB status
voltadmin status

# Check pipeline logs
kubectl logs -f deployment/asset-monitoring | grep -i error
```

### Consumer lag
```bash
# Check lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group asset-monitoring-group --describe

# Reset offsets (if needed)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group asset-monitoring-group --reset-offsets --to-earliest \
  --topic sensor-data --execute
```

## Performance Tuning

### VoltDB
```yaml
# Increase partition count
# Partition table on high-cardinality column
# Use single-partition procedures
```

### VoltSP
```yaml
# configuration.yaml
pipeline:
  parallelism: 8  # Increase for throughput

kafka:
  properties:
    max.poll.records: 10000
    fetch.min.bytes: 1048576
```

### Kubernetes
```yaml
# values.yaml
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
```

## URLs and Ports

| Service | Default Port | Purpose |
|---------|-------------|---------|
| VoltDB | 21211 | Client connections |
| VoltDB | 21212 | Admin port |
| VoltDB | 8080 | HTTP/JSON API |
| Kafka | 9092 | Broker |
| Zookeeper | 2181 | Coordination |

## File Sizes

After build:
- `asset-monitoring-1.0.jar`: ~50 KB (procedures only)
- `asset-monitoring-voltsp-1.0.0.jar`: ~20 KB (pipeline code only)

## 7 Rules Summary

1. **Temperature Threshold** → High alert when temp > 50°C
2. **Sequential Rule** → High alert when pressure > 30 AND temp > 50
3. **Rolling Window** → Medium alert if >50% combined breach over 10 min
4. **Continuous Condition** → High → Critical escalation on repeated breach
5. **Recovery** → Close alerts when temp < threshold
6. **Hierarchical Roll-Up** → Aggregate total/active alerts across tree
7. **Top Contributors** → Rank child nodes by alert count

## Common Workflows

### Add New Asset
```sql
EXEC UpsertAsset 'asset-002', 'Pump 1', 'Machine', 'fleet-001', '{}';
EXEC RegisterTagMapping 'Asset2.Pump_Sensor_01', 'asset-002';
```

### View Asset Tree
```sql
EXEC GetAssetHierarchy;
```

### Check Alert History
```sql
SELECT * FROM ALERT WHERE node_id = 'asset-001' ORDER BY timestamp DESC;
```

### Clear All Data
```sql
DELETE FROM HIERARCHICAL_METRICS;
DELETE FROM ROLLING_WINDOW_AGG;
DELETE FROM TAG_ASSET_MAPPING;
DELETE FROM SENSOR_READING;
DELETE FROM ALERT;
DELETE FROM ASSET;
```

### Redeploy Schema
```bash
sqlcmd < src/main/resources/remove_db.sql
sqlcmd < src/main/resources/ddl.sql
```

---

**For complete details, see:**
- `INTEGRATION_GUIDE.md` - Full deployment guide
- `PROJECT_SUMMARY.md` - Complete documentation
- `asset-monitoring/README.md` - VoltDB details
- `asset-monitoring-voltsp/README.md` - VoltSP details
