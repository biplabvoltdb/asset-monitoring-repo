# Asset Monitoring System - VoltDB Implementation

Complete VoltDB solution for sensor monitoring and alerting with hierarchical asset management.

## Overview

This system processes sensor data streams (temperature and pressure) and implements 7 sophisticated alerting rules:

1. **Temperature Threshold** - Alert when temp > 50°C (Severity: High)
2. **Sequential Rule** - If pressure > 30, monitor temp; if temp > 50, alert (Severity: High)
3. **Rolling Window** - 10-minute window analysis with combined condition evaluation (Severity: Medium)
4. **Continuous Condition** - First breach creates alert (High), continued breach escalates to Critical
5. **Recovery** - Temperature below threshold closes alert
6. **Hierarchical Roll-Up** - Total and active alerts across asset hierarchy
7. **Top Contributors** - Identify top X child nodes by alert count

## Architecture

### Data Model

- **ASSET**: Hierarchical asset structure (Fleet → Machine → Component)
- **ALERT**: Alert records with severity, status, and breach tracking
- **SENSOR_READING**: Time-series sensor data (partitioned by tag_name)
- **TAG_ASSET_MAPPING**: Maps sensor tags to assets (replicated table)
- **ROLLING_WINDOW_AGG**: Aggregated window metrics
- **HIERARCHICAL_METRICS**: Pre-computed hierarchy roll-ups

### Partitioning Strategy

- **ASSET & ALERT**: Partitioned on `node_id` (co-located for efficient queries)
- **SENSOR_READING**: Partitioned on `tag_name` (optimized for time-series ingestion)
- **TAG_ASSET_MAPPING**: Replicated table (fast lookups from any partition)

### Stored Procedures

#### Single-Partition Procedures (Fast)
- `ProcessSensorReading` - Rules 1, 2, 4, 5
- `AnalyzeRollingWindow` - Rule 3
- `EscalateAlert` - Rule 4 escalation
- `RecoverAlert` - Rule 5 recovery
- `UpsertAsset` - Asset management
- `GetAlertsByNode` - Query alerts by asset

#### Multi-Partition Procedures (Global Operations)
- `ComputeHierarchicalMetrics` - Rule 6
- `GetTopContributors` - Rule 7
- `RegisterTagMapping` - Tag registration
- `GetAssetHierarchy` - Full hierarchy query

## Project Structure

```
asset-monitoring/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/com/example/voltdb/
        │   ├── AssetMonitoringApp.java      # Main client application
        │   ├── VoltDBSetup.java              # Schema deployment utility
        │   └── procedures/
        │       ├── ProcessSensorReading.java     # Rules 1, 2, 4, 5
        │       ├── AnalyzeRollingWindow.java     # Rule 3
        │       ├── EscalateAlert.java            # Rule 4 escalation
        │       ├── RecoverAlert.java             # Rule 5 recovery
        │       ├── ComputeHierarchicalMetrics.java # Rule 6
        │       ├── GetTopContributors.java       # Rule 7
        │       ├── UpsertAsset.java              # Asset management
        │       ├── RegisterTagMapping.java       # Tag registration
        │       ├── GetAlertsByNode.java          # Alert queries
        │       └── GetAssetHierarchy.java        # Hierarchy queries
        └── resources/
            ├── ddl.sql                   # Database schema
            └── remove_db.sql             # Schema cleanup
```

## Build and Deploy

### Prerequisites

- Java 17+
- Maven 3.6+
- VoltDB Enterprise (running instance)

### Build

```bash
# Navigate to project directory
cd /Users/biplab/Download/asset-monitoring

# Build the project (compile and package)
mvn clean package -DskipTests
```

This creates `target/asset-monitoring-1.0.jar` with all stored procedures.

### Deploy Schema

The application automatically deploys the schema on first run using `VoltDBSetup.java`.

Alternatively, deploy manually:

```bash
# Using VoltDB CLI (sqlcmd)
sqlcmd < src/main/resources/ddl.sql
```

### Run Application

```bash
# Run with default connection (localhost:21211)
java -jar target/asset-monitoring-1.0.jar

# Run with custom host and port
java -jar target/asset-monitoring-1.0.jar <host> <port>

# Or use Maven
mvn exec:java -Dexec.mainClass="com.example.voltdb.AssetMonitoringApp"
```

## API Usage

### Setup Asset Hierarchy

```java
AssetMonitoringApp app = new AssetMonitoringApp(client);

// Create fleet
app.upsertAsset("fleet-001", "Fleet Alpha", "Fleet", null,
    "{\"description\":\"Primary fleet\"}");

// Create assets under fleet
app.upsertAsset("asset-001", "Asset1", "Machine", "fleet-001",
    "{\"tempThreshold\":50,\"pressureThreshold\":30}");

// Register sensor tag mapping
app.registerTagMapping("Asset1.Compressor_Sensor_01", "asset-001");
```

### Process Sensor Readings

```java
// Process individual reading
VoltTable result = app.processSensorReading(
    "Asset1.Compressor_Sensor_01",  // tag name
    System.currentTimeMillis(),      // timestamp
    55.0,                            // temperature
    35.0,                            // pressure
    1                                // quality (1=good)
);

// Process JSON input (matches sample format)
String json = """
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
""";
app.processJsonInput(json);
```

### Query Alerts

```java
// Get active alerts for an asset
VoltTable activeAlerts = app.getAlertsByNode("asset-001", true);

// Get all alerts (active and closed)
VoltTable allAlerts = app.getAlertsByNode("asset-001", false);
```

### Rolling Window Analysis (Rule 3)

```java
long windowStart = System.currentTimeMillis();
long windowEnd = windowStart + (10 * 60 * 1000); // 10 minutes

VoltTable result = app.analyzeRollingWindow("asset-001", windowStart, windowEnd);
```

### Hierarchical Roll-Up (Rule 6)

```java
// Compute metrics for entire hierarchy
app.computeHierarchicalMetrics();

// Query metrics for specific node
VoltTable fleetMetrics = app.getHierarchicalMetrics("fleet-001");
```

### Top Contributors (Rule 7)

```java
// Get top 5 contributors by total alerts
VoltTable top5 = app.getTopContributors("fleet-001", 5, false);

// Get top 5 by active alerts
VoltTable top5Active = app.getTopContributors("fleet-001", 5, true);
```

## Input Format

The system accepts JSON input in this format:

```json
{
  "tags": [
    {
      "name": "Asset1.Compressor_Sensor_01",
      "results": [
        {
          "values": [
            [timestamp_ms, temperature, quality],
            [1677840060000, 151.2, 3]
          ]
        }
      ]
    }
  ]
}
```

**Note**: Pressure values can be added as a 4th element in the values array if needed.

## Output Format

Alert records contain:

- **alert_id** - Unique identifier
- **title** - Alert description
- **timestamp** - Latest update time
- **severity** - High, Medium, Low, or Critical
- **status** - Active or Closed
- **tag_name** - Sensor tag that triggered alert
- **node_id** - Asset identifier
- **first_breach_time** - When alert was first created
- **breach_count** - Number of continued breaches

## Rule Implementation Details

### Rule 1: Temperature Threshold
Implemented in `ProcessSensorReading.java`. Creates High severity alert when temp > 50°C.

### Rule 2: Sequential Rule
Implemented in `ProcessSensorReading.java`. Monitors pressure; if > 30, checks temp; creates alert if both conditions met.

### Rule 3: Rolling Window
Implemented in `AnalyzeRollingWindow.java`. Maintains 10-minute window, computes percentage of readings with combined breach (temp > 50 AND pressure > 30), creates Medium alert if > 50%.

### Rule 4: Continuous Condition
Implemented in `ProcessSensorReading.java`. First breach creates High alert, subsequent breaches increment `breach_count` and escalate to Critical.

### Rule 5: Recovery
Implemented in `ProcessSensorReading.java`. Automatically closes active alerts when temp drops below threshold.

### Rule 6: Hierarchical Roll-Up
Implemented in `ComputeHierarchicalMetrics.java`. Recursively aggregates alert counts from leaf nodes to root, stores in `HIERARCHICAL_METRICS` table.

### Rule 7: Top Contributors
Implemented in `GetTopContributors.java`. Queries `HIERARCHICAL_METRICS` to rank child nodes by alert count.

## Performance Characteristics

- **Sensor ingestion**: Single-partition writes (fast, parallel)
- **Alert queries by asset**: Single-partition reads (fast)
- **Rolling window analysis**: Single-partition (processes one asset's data)
- **Hierarchical roll-up**: Multi-partition (scans entire hierarchy)
- **Top contributors**: Multi-partition with LIMIT (efficient ranking)

## Database Management

### Clean All Data

```java
app.deleteAllData();
```

### Remove Schema

```bash
sqlcmd < src/main/resources/remove_db.sql
```

### Reset Sequence

```sql
DROP SEQUENCE alert_id_seq IF EXISTS;
CREATE SEQUENCE alert_id_seq;
```

## Integration with VoltSP

This VoltDB solution is designed to work with VoltSP (VoltDB Streaming Platform) for continuous stream processing. VoltSP pipelines can:

1. Ingest sensor data from Kafka/MQTT/other sources
2. Call `ProcessSensorReading` procedure for each reading
3. Trigger `AnalyzeRollingWindow` on time-based windows
4. Schedule periodic `ComputeHierarchicalMetrics` execution
5. Export alerts to downstream systems

See VoltSP documentation for pipeline configuration.

## Troubleshooting

### "Mispartitioned tuple" error
Ensure partition key (node_id or tag_name) is the FIRST parameter in procedure calls.

### No alerts generated
Verify tag is registered in `TAG_ASSET_MAPPING` table before processing readings.

### Rolling window not triggering
Ensure sufficient readings exist in the time window and breach percentage exceeds 50%.

### Hierarchical metrics empty
Run `ComputeHierarchicalMetrics` after creating alerts to populate rollup data.

## License

Copyright (c) 2024. All rights reserved.
