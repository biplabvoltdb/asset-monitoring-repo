-- VoltDB DDL for Real-Time Asset Monitoring PoC
-- All tables are partitioned to support 1.5M asset nodes and ~3K events/sec.

-- 1. ASSET: hierarchical node registry (Fleet/Site/.../Sensor)
CREATE TABLE ASSET (
    NODE_ID    VARCHAR(64) NOT NULL,
    NAME       VARCHAR(128),
    TYPE       VARCHAR(32) DEFAULT 'Sensor' NOT NULL,
    PARENT_ID  VARCHAR(64),
    LEVEL      INTEGER DEFAULT 0 NOT NULL,
    TAGS_JSON  VARCHAR(4096),
    PRIMARY KEY (NODE_ID)
);
PARTITION TABLE ASSET ON COLUMN NODE_ID;
CREATE INDEX IDX_ASSET_PARENT ON ASSET (PARENT_ID);

-- 2. SENSOR_TAG: tag -> node + thresholds (threshold metadata for rule engine)
CREATE TABLE SENSOR_TAG (
    TAG_NAME          VARCHAR(128) NOT NULL,
    NODE_ID           VARCHAR(64)  NOT NULL,
    METRIC            VARCHAR(32) DEFAULT 'TEMPERATURE' NOT NULL, -- TEMPERATURE | PRESSURE
    TEMP_THRESHOLD    FLOAT DEFAULT 50.0  NOT NULL,
    PRESSURE_THRESHOLD FLOAT DEFAULT 30.0 NOT NULL,
    TEMP_HIGH         FLOAT DEFAULT 60.0  NOT NULL, -- Rule 2 escalation threshold
    PRIMARY KEY (TAG_NAME)
);
PARTITION TABLE SENSOR_TAG ON COLUMN TAG_NAME;
CREATE INDEX IDX_SENSOR_TAG_NODE ON SENSOR_TAG (NODE_ID);

-- 3. ALERT: generated alerts (written from the VoltSP processor)
CREATE TABLE ALERT (
    TAG_NAME   VARCHAR(128) NOT NULL,
    ALERT_ID   VARCHAR(64)  NOT NULL,
    NODE_ID    VARCHAR(64)  NOT NULL,
    TITLE      VARCHAR(256),
    SEVERITY   VARCHAR(16)  DEFAULT 'Low'    NOT NULL, -- Low/Medium/High/Critical
    STATUS     VARCHAR(16)  DEFAULT 'Active' NOT NULL, -- Active/Closed
    RULE_ID    VARCHAR(16)  DEFAULT 'R1'     NOT NULL,
    TS         TIMESTAMP    NOT NULL,
    VALUE      FLOAT,
    PRIMARY KEY (TAG_NAME, ALERT_ID)
);
PARTITION TABLE ALERT ON COLUMN TAG_NAME;
CREATE INDEX IDX_ALERT_NODE   ON ALERT (NODE_ID);
CREATE INDEX IDX_ALERT_STATUS ON ALERT (STATUS);

-- 4. SENSOR_WINDOW: rolling 10-min window samples for Rule 3
CREATE TABLE SENSOR_WINDOW (
    TAG_NAME VARCHAR(128) NOT NULL,
    TS       TIMESTAMP    NOT NULL,
    TEMP     FLOAT,
    PRESSURE FLOAT,
    PRIMARY KEY (TAG_NAME, TS)
);
PARTITION TABLE SENSOR_WINDOW ON COLUMN TAG_NAME;

-- 5. HIERARCHY_ROLLUP: precomputed counts per node (refreshed from alerts)
CREATE TABLE HIERARCHY_ROLLUP (
    NODE_ID       VARCHAR(64) NOT NULL,
    TOTAL_ALERTS  BIGINT DEFAULT 0 NOT NULL,
    ACTIVE_ALERTS BIGINT DEFAULT 0 NOT NULL,
    UPDATED_TS    TIMESTAMP,
    PRIMARY KEY (NODE_ID)
);
PARTITION TABLE HIERARCHY_ROLLUP ON COLUMN NODE_ID;

-- ============================================================
-- Stored Procedures
-- ============================================================

-- Asset / sensor-tag administration (single partition on NODE_ID / TAG_NAME)
DROP PROCEDURE com.bh.poc.procedures.UpsertAsset IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ASSET COLUMN NODE_ID
    FROM CLASS com.bh.poc.procedures.UpsertAsset;

DROP PROCEDURE com.bh.poc.procedures.UpsertSensorTag IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE SENSOR_TAG COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.UpsertSensorTag;

-- Threshold / metadata lookup used by the processor BEFORE evaluating a sample
DROP PROCEDURE com.bh.poc.procedures.GetSensorTag IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE SENSOR_TAG COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.GetSensorTag;

-- Alert write path (single-partition on TAG_NAME)
DROP PROCEDURE com.bh.poc.procedures.UpsertAlert IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.UpsertAlert;

DROP PROCEDURE com.bh.poc.procedures.GetActiveAlertByTag IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.GetActiveAlertByTag;

DROP PROCEDURE com.bh.poc.procedures.EscalateAlert IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.EscalateAlert;

DROP PROCEDURE com.bh.poc.procedures.CloseAlert IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE ALERT COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.CloseAlert;

-- Rolling-window helpers (Rule 3)
DROP PROCEDURE com.bh.poc.procedures.AppendWindowSample IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE SENSOR_WINDOW COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.AppendWindowSample;

DROP PROCEDURE com.bh.poc.procedures.EvaluateWindow IF EXISTS;
CREATE PROCEDURE PARTITION ON TABLE SENSOR_WINDOW COLUMN TAG_NAME
    FROM CLASS com.bh.poc.procedures.EvaluateWindow;

-- Real-time analytics (multi-partition)
DROP PROCEDURE com.bh.poc.procedures.HierarchyRollup IF EXISTS;
CREATE PROCEDURE FROM CLASS com.bh.poc.procedures.HierarchyRollup;

DROP PROCEDURE com.bh.poc.procedures.TopContributors IF EXISTS;
CREATE PROCEDURE FROM CLASS com.bh.poc.procedures.TopContributors;
