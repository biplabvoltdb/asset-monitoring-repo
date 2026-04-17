-- VoltDB Remove Schema — drops all objects in dependency order
-- Run this to clean up the database for a fresh start

-- Step 1: Drop procedures first (they reference tables)
DROP PROCEDURE com.example.voltdb.procedures.ProcessSensorReading IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.EscalateAlert IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.RecoverAlert IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.AnalyzeRollingWindow IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.ComputeHierarchicalMetrics IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.GetTopContributors IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.UpsertAsset IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.RegisterTagMapping IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.GetAlertsByNode IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.GetAssetHierarchy IF EXISTS;

-- Step 2: Drop dependent tables
DROP TABLE HIERARCHICAL_METRICS IF EXISTS;
DROP TABLE ROLLING_WINDOW_AGG IF EXISTS;
DROP TABLE TAG_ASSET_MAPPING IF EXISTS;
DROP TABLE SENSOR_READING IF EXISTS;
DROP TABLE ALERT IF EXISTS;

-- Step 3: Drop primary table
DROP TABLE ASSET IF EXISTS;

-- Step 4: Drop sequences (not supported in VoltDB 14.0.1)
