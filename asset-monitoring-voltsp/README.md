# Asset Monitoring VoltSP Pipeline

VoltSP streaming pipelines for real-time sensor monitoring and alerting, integrating with VoltDB for complex event processing.

## Overview

This solution consists of two VoltSP pipelines:

### 1. Main Pipeline (`AssetMonitoringPipeline`)
- Consumes sensor data from Kafka topic `sensor-data`
- Parses JSON sensor readings (temperature, pressure, quality)
- Calls VoltDB `ProcessSensorReading` stored procedure
- Implements Rules 1, 2, 4, 5:
  - Rule 1: Temperature Threshold
  - Rule 2: Sequential Rule (pressure + temp)
  - Rule 4: Continuous Condition (escalation)
  - Rule 5: Recovery
- Routes alerts to severity-based Kafka topics:
  - `alerts-high` - High severity alerts
  - `alerts-medium` - Medium severity alerts
  - `alerts-critical` - Critical severity alerts (escalated)

### 2. Rolling Window Pipeline (`RollingWindowPipeline`)
- Scheduled execution every 5 minutes
- Analyzes 10-minute rolling windows for all assets (Rule 3)
- Computes hierarchical metrics across asset tree (Rule 6)
- Calls VoltDB procedures:
  - `AnalyzeRollingWindow` - for each asset
  - `ComputeHierarchicalMetrics` - for hierarchy roll-up

## Project Structure

```
asset-monitoring-voltsp/
├── pom.xml
├── README.md
├── configuration.yaml                    # Main pipeline config
├── configuration-rolling-window.yaml     # Rolling window pipeline config
├── configurationSecure.yaml              # Secure config (credentials)
├── helm/
│   ├── values-main-pipeline.yaml         # Helm values for main pipeline
│   └── values-rolling-window.yaml        # Helm values for rolling window
└── src/main/java/com/example/voltsp/
    ├── AssetMonitoringPipeline.java      # Main streaming pipeline
    └── RollingWindowPipeline.java        # Scheduled analysis pipeline
```

## Prerequisites

- Java 17+
- Maven 3.6+
- VoltDB Enterprise (with asset-monitoring schema deployed)
- Kafka (for input and output)
- VoltSP CLI or Kubernetes cluster with Helm (for deployment)

## Build

```bash
# Navigate to project directory
cd /Users/biplab/Download/asset-monitoring-voltsp

# Build pipeline JAR
mvn clean package
```

This creates `target/asset-monitoring-voltsp-1.0.0.jar`.

## Local Deployment (Bare Metal)

### 1. Deploy Main Pipeline

```bash
# Set environment variables
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export VOLTDB_HOST="localhost"
export VOLTDB_PORT="21211"

# Run with VoltSP CLI
voltsp run \
  --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
  --class com.example.voltsp.AssetMonitoringPipeline \
  --config configuration.yaml \
  --configSecure configurationSecure.yaml \
  --license $VOLTDB_LICENSE
```

### 2. Deploy Rolling Window Pipeline

```bash
# Run as separate VoltSP instance
voltsp run \
  --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
  --class com.example.voltsp.RollingWindowPipeline \
  --config configuration-rolling-window.yaml \
  --license $VOLTDB_LICENSE
```

## Kubernetes Deployment (Helm)

### Prerequisites

```bash
# Add VoltDB Helm repo
helm repo add voltdb https://voltdb-kubernetes-charts.storage.googleapis.com
helm repo update

# Set license file path
export VOLTDB_LICENSE=$HOME/licenses/volt-license.xml
```

### 1. Deploy Main Pipeline

```bash
helm install asset-monitoring voltdb/volt-streams \
  --set-file streaming.licenseXMLFile=${VOLTDB_LICENSE} \
  --set-file streaming.voltapps=target/asset-monitoring-voltsp-1.0.0.jar \
  --values helm/values-main-pipeline.yaml
```

### 2. Deploy Rolling Window Pipeline

```bash
helm install rolling-window voltdb/volt-streams \
  --set-file streaming.licenseXMLFile=${VOLTDB_LICENSE} \
  --set-file streaming.voltapps=target/asset-monitoring-voltsp-1.0.0.jar \
  --values helm/values-rolling-window.yaml
```

### 3. Monitor Deployments

```bash
# Check pod status
kubectl get pods -l app.kubernetes.io/instance=asset-monitoring
kubectl get pods -l app.kubernetes.io/instance=rolling-window

# View logs
kubectl logs -f deployment/asset-monitoring
kubectl logs -f deployment/rolling-window

# Scale main pipeline
kubectl scale deployment asset-monitoring --replicas=5
```

## Configuration

### Main Pipeline Configuration (`configuration.yaml`)

```yaml
kafka:
  bootstrap:
    servers: "localhost:9092"
  input:
    topic: "sensor-data"
  consumer:
    group: "asset-monitoring-group"
  output:
    topic:
      high: "alerts-high"
      medium: "alerts-medium"
      critical: "alerts-critical"

voltdb:
  host: "localhost"
  port: 21211

pipeline:
  parallelism: 4
```

### Rolling Window Configuration (`configuration-rolling-window.yaml`)

```yaml
voltdb:
  host: "localhost"
  port: 21211

window:
  duration:
    ms: 600000  # 10 minutes

schedule:
  interval:
    seconds: 300  # Every 5 minutes
```

### Environment Variable Interpolation

Configuration supports runtime interpolation:

- `${env:VAR}` - Environment variable
- `${env:VAR:DEFAULT}` - Environment variable with default
- `${secret:env:VAR}` - Secret environment variable (not logged)
- `${aws:secret-name}` - AWS Secrets Manager

Example:
```yaml
kafka:
  bootstrap:
    servers: "${env:KAFKA_BOOTSTRAP_SERVERS:localhost:9092}"
```

## Input Format

Kafka messages in `sensor-data` topic should be JSON:

```json
{
  "tags": [
    {
      "name": "Asset1.Compressor_Sensor_01",
      "results": [
        {
          "values": [
            [1677840000000, 150.5, 1],
            [1677840060000, 151.2, 3]
          ]
        }
      ]
    }
  ]
}
```

Where each value array is: `[timestamp_ms, temperature, quality, pressure (optional)]`

## Output Format

Alerts are published to Kafka topics as JSON:

```json
{
  "tagName": "Asset1.Compressor_Sensor_01",
  "timestamp": 1677840000000,
  "temperature": 55.5,
  "pressure": 35.0,
  "status": "ALERT_CREATED",
  "message": "Sequential Rule Breach: High Pressure and Temperature"
}
```

## Pipeline Flow

### Main Pipeline Flow

```
Kafka (sensor-data)
  → Parse JSON
  → Extract sensor readings
  → Call VoltDB ProcessSensorReading
  → Route alerts by severity
  → Kafka (alerts-high/medium/critical)
```

### Rolling Window Pipeline Flow

```
Scheduled Timer (every 5 min)
  → Get all assets
  → For each asset: Call AnalyzeRollingWindow
  → Call ComputeHierarchicalMetrics
  → Log results
```

## Performance Tuning

### Kafka Consumer Settings

Add to `configuration.yaml`:
```yaml
kafka:
  properties:
    max.poll.records: 10000
    max.poll.interval.ms: 300000
    fetch.min.bytes: 1024
```

### VoltDB Connection Tuning

Add to Helm values:
```yaml
streaming:
  javaProperties:
    voltsp.commit.async.timeout.ms: "30000"
```

### Horizontal Scaling

Main pipeline supports horizontal scaling via Helm:
```yaml
autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
```

## Monitoring

### VoltSP Metrics

VoltSP exposes metrics at `/metrics` endpoint (if enabled):
- `voltsp_records_in_total` - Total records consumed
- `voltsp_records_out_total` - Total records produced
- `voltsp_processing_latency_ms` - Processing latency

### Kafka Monitoring

Monitor consumer lag:
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group asset-monitoring-group --describe
```

### VoltDB Monitoring

Check procedure execution stats:
```sql
SELECT PROCEDURE, INVOCATIONS, AVG_EXECUTION_TIME
FROM PROCEDURE_STATISTICS
WHERE PROCEDURE IN (
  'ProcessSensorReading',
  'AnalyzeRollingWindow',
  'ComputeHierarchicalMetrics'
);
```

## Error Handling

The pipeline includes error handling:

1. **JSON Parse Errors**: Logged and skipped, processing continues
2. **VoltDB Connection Errors**: Retried automatically
3. **Kafka Producer Errors**: Retried with backoff
4. **General Exceptions**: Logged, processing continues

View errors in logs:
```bash
kubectl logs -f deployment/asset-monitoring | grep ERROR
```

## Integration with VoltDB

The pipeline requires the VoltDB asset-monitoring schema to be deployed first:

1. Deploy VoltDB schema (from asset-monitoring project)
2. Verify procedures exist:
   ```sql
   SELECT PROCEDURE_NAME FROM PROCEDURES
   WHERE PROCEDURE_NAME IN (
     'ProcessSensorReading',
     'AnalyzeRollingWindow',
     'ComputeHierarchicalMetrics'
   );
   ```
3. Start VoltSP pipelines

## Testing

### Generate Test Data

Produce test messages to Kafka:

```bash
echo '{
  "tags": [{
    "name": "Asset1.Compressor_Sensor_01",
    "results": [{
      "values": [
        ['$(date +%s000)', 55.5, 1, 35.0]
      ]
    }]
  }]
}' | kafka-console-producer --broker-list localhost:9092 --topic sensor-data
```

### Verify Alerts

Consume from alert topics:

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic alerts-high --from-beginning

kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic alerts-critical --from-beginning
```

## Troubleshooting

### Pipeline Not Starting

Check logs:
```bash
kubectl logs -f deployment/asset-monitoring
```

Common issues:
- Missing VoltDB connection
- Kafka broker not reachable
- Invalid license file

### No Alerts Generated

Verify:
1. Sensor data arriving in Kafka: `kafka-console-consumer --topic sensor-data`
2. VoltDB procedures callable: `sqlcmd` and test procedures
3. Tag mappings registered in VoltDB: `SELECT * FROM TAG_ASSET_MAPPING;`

### High Latency

Tune parallelism:
```yaml
pipeline:
  parallelism: 8  # Increase for higher throughput
```

Scale replicas:
```bash
kubectl scale deployment asset-monitoring --replicas=5
```

## Architecture Diagram

```
                    ┌─────────────────┐
                    │  Kafka (Input)  │
                    │  sensor-data    │
                    └────────┬────────┘
                             │
                    ┌────────▼─────────┐
                    │   VoltSP Main    │
                    │    Pipeline      │◄──── configuration.yaml
                    │ (AssetMonitoring)│
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │     VoltDB       │
                    │  ProcessSensor   │
                    │    Reading       │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
         ┌────▼────┐   ┌────▼────┐   ┌────▼────┐
         │ Kafka   │   │ Kafka   │   │ Kafka   │
         │alerts-  │   │alerts-  │   │alerts-  │
         │ high    │   │ medium  │   │critical │
         └─────────┘   └─────────┘   └─────────┘


         ┌─────────────────┐
         │  Scheduled      │
         │  Timer          │
         │  (5 minutes)    │
         └────────┬────────┘
                  │
         ┌────────▼─────────┐
         │   VoltSP         │
         │   Rolling Window │◄──── configuration-rolling-window.yaml
         │   Pipeline       │
         └────────┬─────────┘
                  │
         ┌────────▼─────────┐
         │     VoltDB       │
         │  AnalyzeRolling  │
         │  Window +        │
         │  Hierarchical    │
         │  Metrics         │
         └──────────────────┘
```

## License

Copyright (c) 2024. All rights reserved.
