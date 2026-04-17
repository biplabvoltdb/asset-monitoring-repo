package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

/**
 * Rule 3: Rolling Window Analysis
 *
 * Maintains a 10-minute window per asset and evaluates combined conditions
 * (compute avg condition occurrence for Temp > 50 & Pressure > 30).
 * Creates Medium severity alert if thresholds are met.
 *
 * This procedure is partitioned on node_id (first parameter).
 */
public class AnalyzeRollingWindow extends VoltProcedure {

    // Get all sensor readings for this asset's tags in the time window
    public final SQLStmt getReadingsInWindow = new SQLStmt(
        "SELECT sr.temperature, sr.pressure, sr.timestamp " +
        "FROM SENSOR_READING sr " +
        "JOIN TAG_ASSET_MAPPING tam ON sr.tag_name = tam.tag_name " +
        "WHERE tam.node_id = ? AND sr.timestamp >= ? AND sr.timestamp <= ?;"
    );

    // Upsert rolling window aggregate
    public final SQLStmt upsertWindowAgg = new SQLStmt(
        "UPSERT INTO ROLLING_WINDOW_AGG " +
        "(node_id, window_start, window_end, avg_temp_breach_count, avg_pressure_breach_count, combined_breach_count) " +
        "VALUES (?, ?, ?, ?, ?, ?);"
    );

    // Check for existing rolling window alert
    public final SQLStmt checkRollingAlert = new SQLStmt(
        "SELECT alert_id FROM ALERT " +
        "WHERE node_id = ? AND title LIKE 'Rolling Window%' AND status = 'Active' " +
        "ORDER BY alert_id DESC LIMIT 1;"
    );

    // Create rolling window alert
    public final SQLStmt createRollingAlert = new SQLStmt(
        "INSERT INTO ALERT (alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count) " +
        "VALUES (?, ?, 'ROLLING_WINDOW', ?, ?, 'Medium', 'Active', ?, 1);"
    );

    /**
     * Analyze rolling window for an asset.
     *
     * @param nodeId - partition key (MUST be first parameter)
     * @param windowStart - start of 10-minute window (milliseconds)
     * @param windowEnd - end of 10-minute window (milliseconds)
     * @return VoltTable with analysis result
     */
    public VoltTable[] run(String nodeId, long windowStart, long windowEnd) throws VoltAbortException {

        // Get all sensor readings in this window for this asset
        voltQueueSQL(getReadingsInWindow, nodeId, windowStart, windowEnd);
        VoltTable[] results = voltExecuteSQL();
        VoltTable readings = results[0];

        if (readings.getRowCount() == 0) {
            return new VoltTable[] { createResultTable("NO_DATA", "No sensor readings in window", 0, 0, 0) };
        }

        final double TEMP_THRESHOLD = 50.0;
        final double PRESSURE_THRESHOLD = 30.0;
        final double COMBINED_BREACH_THRESHOLD = 0.5; // 50% of readings

        int totalReadings = readings.getRowCount();
        int tempBreachCount = 0;
        int pressureBreachCount = 0;
        int combinedBreachCount = 0;

        while (readings.advanceRow()) {
            double temp = readings.getDouble("temperature");
            double pressure = readings.getDouble("pressure");

            boolean tempBreach = temp > TEMP_THRESHOLD;
            boolean pressureBreach = pressure > PRESSURE_THRESHOLD;

            if (tempBreach) tempBreachCount++;
            if (pressureBreach) pressureBreachCount++;
            if (tempBreach && pressureBreach) combinedBreachCount++;
        }

        // Store window aggregate
        voltQueueSQL(upsertWindowAgg, nodeId, windowStart, windowEnd,
                     tempBreachCount, pressureBreachCount, combinedBreachCount);

        // Calculate combined breach percentage
        double combinedBreachPct = (double) combinedBreachCount / totalReadings;

        // Create alert if threshold exceeded
        if (combinedBreachPct >= COMBINED_BREACH_THRESHOLD) {
            voltQueueSQL(checkRollingAlert, nodeId);
            VoltTable[] alertResults = voltExecuteSQL();

            if (alertResults[0].getRowCount() == 0) {
                // Create new rolling window alert
                String title = String.format("Rolling Window Alert: %.0f%% combined breach over 10 minutes",
                                            combinedBreachPct * 100);
                long alertId = windowEnd * 1000 + Math.abs(nodeId.hashCode() % 1000);
                voltQueueSQL(createRollingAlert, alertId, nodeId, title, windowEnd, windowStart);
                voltExecuteSQL();
                return new VoltTable[] { createResultTable("ALERT_CREATED", title,
                                                           tempBreachCount, pressureBreachCount, combinedBreachCount) };
            } else {
                voltExecuteSQL();
                return new VoltTable[] { createResultTable("ALERT_EXISTS", "Rolling window alert already active",
                                                           tempBreachCount, pressureBreachCount, combinedBreachCount) };
            }
        } else {
            voltExecuteSQL();
            return new VoltTable[] { createResultTable("NO_ALERT", "Combined breach below threshold",
                                                       tempBreachCount, pressureBreachCount, combinedBreachCount) };
        }
    }

    private VoltTable createResultTable(String status, String message, int tempBreach, int pressureBreach, int combinedBreach) {
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("status", VoltType.STRING),
            new VoltTable.ColumnInfo("message", VoltType.STRING),
            new VoltTable.ColumnInfo("temp_breach_count", VoltType.INTEGER),
            new VoltTable.ColumnInfo("pressure_breach_count", VoltType.INTEGER),
            new VoltTable.ColumnInfo("combined_breach_count", VoltType.INTEGER)
        );
        result.addRow(status, message, tempBreach, pressureBreach, combinedBreach);
        return result;
    }
}
