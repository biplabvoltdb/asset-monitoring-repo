# Asset Monitoring Complete Solution - Integration Guide

This guide explains how to deploy and integrate the complete Asset Monitoring solution using both VoltDB and VoltSP.

## Solution Overview

The complete solution consists of two components:

### 1. VoltDB Backend (`asset-monitoring/`)
- Database schema with partitioned tables for assets, alerts, and sensor readings
- 10 stored procedures implementing 7 alerting rules
- Java client application for testing and data loading
- Hierarchical asset management and metrics roll-up

### 2. VoltSP Streaming Pipelines (`asset-monitoring-voltsp/`)
- Real-time sensor data ingestion from Kafka
- Stream processing with VoltDB procedure calls
- Alert routing to severity-based Kafka topics
- Scheduled rolling window analysis and metrics computation

## Architecture

```
┌────────────────┐
│  Sensor Data   │
│  (Kafka Input) │
└────────┬───────┘
         │
         ▼
┌─────────────────────────────┐
│  VoltSP Main Pipeline       │
│  - Parse JSON               │
│  - Extract readings         │
│  - Process rules 1,2,4,5    │
└────────┬────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│  VoltDB                     │
│  - ASSET tables             │
│  - ALERT tables             │
│  - SENSOR_READING tables    │
│  - Stored procedures        │
└────────┬────────────────────┘
         │
         ├──► Rules 1,2,4,5 (real-time)
         └──► Rules 3,6 (scheduled)
                    ▲
                    │
         ┌──────────┴─────────┐
         │  VoltSP Rolling    │
         │  Window Pipeline   │
         │  - Every 5 minutes │
         └────────────────────┘

         ┌────────────────────┐
         │  Alert Output      │
         │  (Kafka Topics)    │
         │  - alerts-high     │
         │  - alerts-medium   │
         │  - alerts-critical │
         └────────────────────┘
```

## Deployment Steps

### Step 1: Deploy VoltDB Database

```bash
# Navigate to VoltDB project
cd /Users/biplab/Download/asset-monitoring

# Build VoltDB application
mvn clean package -DskipTests

# Start VoltDB server (if not already running)
voltdb init --config=deployment.xml
voltdb start

# Deploy schema and procedures
java -cp "target/asset-monitoring-1.0.jar:target/lib/*" \
  com.example.voltdb.AssetMonitoringApp localhost 21211
```

The application will:
1. Deploy DDL schema from `src/main/resources/ddl.sql`
2. Load stored procedure classes
3. Create sample asset hierarchy
4. Register tag mappings

Verify deployment:
```sql
sqlcmd
SELECT COUNT(*) FROM ASSET;
SELECT COUNT(*) FROM TAG_ASSET_MAPPING;
EXEC PROCEDURES;  -- Should show all 10 procedures
```

### Step 2: Setup Kafka Topics

```bash
# Create input topic for sensor data
kafka-topics --create --topic sensor-data \
  --bootstrap-server localhost:9092 \
  --partitions 4 --replication-factor 1

# Create output topics for alerts
kafka-topics --create --topic alerts-high \
  --bootstrap-server localhost:9092 \
  --partitions 4 --replication-factor 1

kafka-topics --create --topic alerts-medium \
  --bootstrap-server localhost:9092 \
  --partitions 4 --replication-factor 1

kafka-topics --create --topic alerts-critical \
  --bootstrap-server localhost:9092 \
  --partitions 4 --replication-factor 1

# Verify topics
kafka-topics --list --bootstrap-server localhost:9092
```

### Step 3: Deploy VoltSP Pipelines

```bash
# Navigate to VoltSP project
cd /Users/biplab/Download/asset-monitoring-voltsp

# Build pipeline JAR
mvn clean package

# Option A: Use deployment script (interactive)
./deploy.sh

# Option B: Manual deployment
export VOLTDB_LICENSE=/path/to/voltdb-license.xml
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export VOLTDB_HOST=localhost
export VOLTDB_PORT=21211

# Start main pipeline
voltsp run \
  --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
  --class com.example.voltsp.AssetMonitoringPipeline \
  --config configuration.yaml \
  --license $VOLTDB_LICENSE &

# Start rolling window pipeline
voltsp run \
  --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
  --class com.example.voltsp.RollingWindowPipeline \
  --config configuration-rolling-window.yaml \
  --license $VOLTDB_LICENSE &
```

## Testing the Complete Solution

### 1. Setup Test Data

Register assets and tag mappings (if not done in Step 1):

```java
// Using VoltDB client
AssetMonitoringApp app = new AssetMonitoringApp(client);

app.upsertAsset("fleet-001", "Fleet Alpha", "Fleet", null, "{}");
app.upsertAsset("asset-001", "Compressor 1", "Machine", "fleet-001", "{}");
app.registerTagMapping("Asset1.Compressor_Sensor_01", "asset-001");
```

Or via SQL:
```sql
sqlcmd

EXEC UpsertAsset 'fleet-001', 'Fleet Alpha', 'Fleet', NULL, '{}';
EXEC UpsertAsset 'asset-001', 'Compressor 1', 'Machine', 'fleet-001', '{}';
EXEC RegisterTagMapping 'Asset1.Compressor_Sensor_01', 'asset-001';
```

### 2. Send Test Sensor Data

Produce sensor data to Kafka:

```bash
# Test 1: Normal reading (no alert)
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 45.0, 1, 20.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Test 2: Temperature breach (High alert - Rule 1)
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 55.0, 1, 20.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Test 3: Sequential rule breach (High alert - Rule 2)
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 55.0, 1, 35.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# Test 4: Continued breach (Critical alert - Rule 4)
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 60.0, 1, 40.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data
```

### 3. Verify Alerts

Monitor alert topics:

```bash
# Monitor high severity alerts
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic alerts-high --from-beginning

# Monitor critical alerts
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic alerts-critical --from-beginning
```

Check alerts in VoltDB:

```sql
sqlcmd

-- Active alerts
SELECT alert_id, node_id, tag_name, title, severity, status, breach_count
FROM ALERT WHERE status = 'Active';

-- All alerts for an asset
EXEC GetAlertsByNode 'asset-001', false;

-- Hierarchical metrics
SELECT * FROM HIERARCHICAL_METRICS;

-- Top contributors
EXEC GetTopContributors 'fleet-001', 5, false;
```

### 4. Test Rolling Window Analysis (Rule 3)

Generate multiple readings over 10 minutes:

```bash
# Script to generate readings every 30 seconds
for i in {1..20}; do
  TEMP=$(awk -v min=45 -v max=60 'BEGIN{srand(); print min+rand()*(max-min)}')
  PRESSURE=$(awk -v min=25 -v max=40 'BEGIN{srand(); print min+rand()*(max-min)}')

  echo "{
    \"tags\": [{
      \"name\": \"Asset1.Compressor_Sensor_01\",
      \"results\": [{
        \"values\": [
          [$(date +%s000), $TEMP, 1, $PRESSURE]
        ]
      }]
    }]
  }" | kafka-console-producer --broker-list localhost:9092 --topic sensor-data

  sleep 30
done
```

Wait for the rolling window pipeline to run (every 5 minutes), then check:

```sql
-- Rolling window aggregates
SELECT * FROM ROLLING_WINDOW_AGG WHERE node_id = 'asset-001';

-- Medium severity alerts from rolling window
SELECT * FROM ALERT WHERE title LIKE 'Rolling Window%';
```

### 5. Test Recovery (Rule 5)

Send normal temperature reading:

```bash
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 40.0, 1, 20.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data
```

Verify alerts are closed:

```sql
SELECT alert_id, status, timestamp
FROM ALERT
WHERE tag_name = 'Asset1.Compressor_Sensor_01'
ORDER BY timestamp DESC;
```

## Monitoring and Observability

### VoltDB Monitoring

```sql
-- Procedure execution statistics
SELECT PROCEDURE, INVOCATIONS, AVG_EXECUTION_TIME, MIN_EXECUTION_TIME, MAX_EXECUTION_TIME
FROM PROCEDURE_STATISTICS
WHERE PROCEDURE LIKE '%Sensor%' OR PROCEDURE LIKE '%Alert%'
ORDER BY INVOCATIONS DESC;

-- Table sizes
SELECT TABLE_NAME, TUPLE_COUNT
FROM TABLE_STATISTICS
WHERE TABLE_NAME IN ('ASSET', 'ALERT', 'SENSOR_READING');

-- Partition distribution
SELECT PARTITION_ID, COUNT(*) as ALERT_COUNT
FROM ALERT
GROUP BY PARTITION_ID;
```

### VoltSP Pipeline Monitoring

```bash
# View pipeline logs
tail -f /var/log/voltsp/asset-monitoring.log

# Kubernetes
kubectl logs -f deployment/asset-monitoring
kubectl logs -f deployment/rolling-window

# Check consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group asset-monitoring-group --describe
```

### Kafka Monitoring

```bash
# Topic message counts
kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic sensor-data --time -1

kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic alerts-high --time -1
```

## Performance Tuning

### VoltDB Tuning

```sql
-- Update table statistics
EXEC @UpdateApplicationCatalog null, null;
EXEC @Statistics TABLE 0;

-- Check for hot partitions
SELECT PARTITION_ID, COUNT(*) as ROW_COUNT
FROM SENSOR_READING
GROUP BY PARTITION_ID
ORDER BY ROW_COUNT DESC;
```

### VoltSP Tuning

Edit `configuration.yaml`:

```yaml
pipeline:
  parallelism: 8  # Increase for higher throughput

kafka:
  properties:
    max.poll.records: 10000
    fetch.min.bytes: 1048576
    max.poll.interval.ms: 300000
```

### Horizontal Scaling

```bash
# Kubernetes - scale main pipeline
kubectl scale deployment asset-monitoring --replicas=5

# Check distribution
kubectl get pods -l app.kubernetes.io/instance=asset-monitoring \
  -o wide
```

## Troubleshooting

### Issue: Alerts not generated

**Check:**
1. Tag mapping exists:
   ```sql
   SELECT * FROM TAG_ASSET_MAPPING WHERE tag_name = 'Asset1.Compressor_Sensor_01';
   ```

2. VoltSP pipeline processing messages:
   ```bash
   kafka-console-consumer --topic sensor-data --from-beginning
   ```

3. VoltDB procedure callable:
   ```sql
   EXEC ProcessSensorReading 'Asset1.Compressor_Sensor_01', 1677840000000, 55.0, 35.0, 1;
   ```

### Issue: Pipeline connection errors

**Check:**
1. VoltDB running: `voltadmin status`
2. Network connectivity: `telnet localhost 21211`
3. Firewall rules allow connections
4. Configuration has correct host/port

### Issue: High latency

**Actions:**
1. Check VoltDB partition distribution
2. Increase VoltSP parallelism
3. Scale VoltSP replicas
4. Tune Kafka consumer settings
5. Review VoltDB procedure execution times

## Complete End-to-End Example

Here's a complete workflow from scratch:

```bash
# 1. Start VoltDB
cd asset-monitoring
mvn clean package -DskipTests
voltdb init && voltdb start

# 2. Deploy schema
java -cp "target/asset-monitoring-1.0.jar:target/lib/*" \
  com.example.voltdb.VoltDBSetup localhost 21211

# 3. Create Kafka topics
kafka-topics --create --topic sensor-data --partitions 4 --bootstrap-server localhost:9092
kafka-topics --create --topic alerts-high --partitions 4 --bootstrap-server localhost:9092
kafka-topics --create --topic alerts-medium --partitions 4 --bootstrap-server localhost:9092
kafka-topics --create --topic alerts-critical --partitions 4 --bootstrap-server localhost:9092

# 4. Deploy VoltSP pipelines
cd ../asset-monitoring-voltsp
mvn clean package
./deploy.sh

# 5. Send test data
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [['$(date +%s000)', 55.0, 1, 35.0]]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data

# 6. Verify alerts
kafka-console-consumer --bootstrap-server localhost:9092 --topic alerts-high --from-beginning

# 7. Check VoltDB
sqlcmd
SELECT * FROM ALERT WHERE status = 'Active';
```

## Production Deployment Checklist

- [ ] VoltDB cluster deployed with replication
- [ ] Kafka cluster with sufficient partitions
- [ ] VoltSP pipelines deployed to Kubernetes with HPA
- [ ] Monitoring configured (Prometheus, Grafana)
- [ ] Alerting configured for pipeline failures
- [ ] Backup and recovery procedures tested
- [ ] Security configured (TLS, authentication)
- [ ] Resource limits set appropriately
- [ ] Log aggregation configured
- [ ] Performance baseline established

## Summary

This complete solution provides:

✅ **7 Alerting Rules** - All implemented and tested
✅ **Real-time Processing** - Sub-second latency via VoltSP
✅ **Horizontal Scalability** - Both VoltDB and VoltSP scale
✅ **High Availability** - Kafka + VoltDB replication
✅ **Hierarchical Metrics** - Asset tree aggregation
✅ **Flexible Deployment** - Bare metal or Kubernetes
✅ **Comprehensive Monitoring** - Full observability stack

For more details, see:
- VoltDB Solution: `/Users/biplab/Download/asset-monitoring/README.md`
- VoltSP Pipelines: `/Users/biplab/Download/asset-monitoring-voltsp/README.md`
