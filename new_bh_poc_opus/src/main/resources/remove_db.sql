-- Drop in reverse dependency order
DROP PROCEDURE com.bh.poc.procedures.TopContributors        IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.HierarchyRollup        IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.EvaluateWindow         IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.AppendWindowSample     IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.CloseAlert             IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.EscalateAlert          IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.GetActiveAlertByTag    IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.UpsertAlert            IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.GetSensorTag           IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.UpsertSensorTag        IF EXISTS;
DROP PROCEDURE com.bh.poc.procedures.UpsertAsset            IF EXISTS;

DROP TABLE HIERARCHY_ROLLUP IF EXISTS;
DROP TABLE SENSOR_WINDOW    IF EXISTS;
DROP TABLE ALERT            IF EXISTS;
DROP TABLE SENSOR_TAG       IF EXISTS;
DROP TABLE ASSET            IF EXISTS;
