-- VoltDB DDL Schema for Asset Monitoring and Alerting System

-- Asset Entity Table (hierarchical structure)
CREATE TABLE ASSET (
    node_id varchar(36) NOT NULL,
    name varchar(255) NOT NULL,
    type varchar(64) NOT NULL,
    parent varchar(36),
    tags varchar(4096),
    PRIMARY KEY (node_id)
);
PARTITION TABLE ASSET ON COLUMN node_id;

-- Alert Table (co-located with ASSET by node_id)
CREATE TABLE ALERT (
    alert_id bigint NOT NULL,
    node_id varchar(36) NOT NULL,
    tag_name varchar(255) NOT NULL,
    title varchar(255) NOT NULL,
    timestamp bigint NOT NULL,
    severity varchar(32) DEFAULT 'Medium' NOT NULL,
    status varchar(32) DEFAULT 'Active' NOT NULL,
    first_breach_time bigint,
    breach_count int DEFAULT 0 NOT NULL,
    PRIMARY KEY (node_id, alert_id)
);
PARTITION TABLE ALERT ON COLUMN node_id;
CREATE INDEX alert_node_status_idx ON ALERT (node_id, status);
CREATE INDEX alert_tag_status_idx ON ALERT (tag_name, status);

-- Sensor Reading Table (partitioned by tag_name for time-series efficiency)
CREATE TABLE SENSOR_READING (
    tag_name varchar(255) NOT NULL,
    timestamp bigint NOT NULL,
    temperature float,
    pressure float,
    quality int DEFAULT 1 NOT NULL,
    PRIMARY KEY (tag_name, timestamp)
);
PARTITION TABLE SENSOR_READING ON COLUMN tag_name;

-- Tag to Asset Mapping (lookup table to map tag_name to node_id)
-- Replicated table for fast lookups from any partition
CREATE TABLE TAG_ASSET_MAPPING (
    tag_name varchar(255) NOT NULL,
    node_id varchar(36) NOT NULL,
    PRIMARY KEY (tag_name)
);

-- Rolling Window Aggregation Table (for Rule 3)
-- Stores 10-minute aggregated data per asset
CREATE TABLE ROLLING_WINDOW_AGG (
    node_id varchar(36) NOT NULL,
    window_start bigint NOT NULL,
    window_end bigint NOT NULL,
    avg_temp_breach_count int DEFAULT 0 NOT NULL,
    avg_pressure_breach_count int DEFAULT 0 NOT NULL,
    combined_breach_count int DEFAULT 0 NOT NULL,
    PRIMARY KEY (node_id, window_start)
);
PARTITION TABLE ROLLING_WINDOW_AGG ON COLUMN node_id;

-- Hierarchical Metrics Table (for Rule 6)
-- Pre-computed aggregates for each node in the hierarchy
CREATE TABLE HIERARCHICAL_METRICS (
    node_id varchar(36) NOT NULL,
    total_alerts bigint DEFAULT 0 NOT NULL,
    total_active_alerts bigint DEFAULT 0 NOT NULL,
    last_updated bigint NOT NULL,
    PRIMARY KEY (node_id)
);
PARTITION TABLE HIERARCHICAL_METRICS ON COLUMN node_id;

-- Sequence for alert IDs

-- ========================================
-- Stored Procedures
-- ========================================

-- Rule 1 & 2: Temperature Threshold and Sequential Rule
DROP PROCEDURE com.example.voltdb.procedures.ProcessSensorReading IF EXISTS;
CREATE PROCEDURE 
    FROM CLASS com.example.voltdb.procedures.ProcessSensorReading;

-- Rule 4: Continuous Condition (escalate severity)
DROP PROCEDURE com.example.voltdb.procedures.EscalateAlert IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN node_id
    FROM CLASS com.example.voltdb.procedures.EscalateAlert;

-- Rule 5: Recovery (close alert)
DROP PROCEDURE com.example.voltdb.procedures.RecoverAlert IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN node_id
    FROM CLASS com.example.voltdb.procedures.RecoverAlert;

-- Rule 3: Rolling Window Analysis
DROP PROCEDURE com.example.voltdb.procedures.AnalyzeRollingWindow IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ROLLING_WINDOW_AGG COLUMN node_id
    FROM CLASS com.example.voltdb.procedures.AnalyzeRollingWindow;

-- Rule 6: Hierarchical Roll-Up (multi-partition)
DROP PROCEDURE com.example.voltdb.procedures.ComputeHierarchicalMetrics IF EXISTS;
CREATE PROCEDURE FROM CLASS com.example.voltdb.procedures.ComputeHierarchicalMetrics;

-- Rule 7: Top Contributors (multi-partition)
DROP PROCEDURE com.example.voltdb.procedures.GetTopContributors IF EXISTS;
CREATE PROCEDURE FROM CLASS com.example.voltdb.procedures.GetTopContributors;

-- Utility procedures
DROP PROCEDURE com.example.voltdb.procedures.UpsertAsset IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ASSET COLUMN node_id
    FROM CLASS com.example.voltdb.procedures.UpsertAsset;

DROP PROCEDURE com.example.voltdb.procedures.RegisterTagMapping IF EXISTS;
CREATE PROCEDURE FROM CLASS com.example.voltdb.procedures.RegisterTagMapping;

DROP PROCEDURE com.example.voltdb.procedures.GetAlertsByNode IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN node_id
    FROM CLASS com.example.voltdb.procedures.GetAlertsByNode;

DROP PROCEDURE com.example.voltdb.procedures.GetAssetHierarchy IF EXISTS;
CREATE PROCEDURE FROM CLASS com.example.voltdb.procedures.GetAssetHierarchy;
