package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Combined procedure: Insert sensor reading + create/update alert.
 * Called by VoltSP when rules detect a threshold breach.
 * Partitioned on tag_name.
 */
public class ProcessSensorWithAlert extends VoltProcedure {

    public final SQLStmt upsertReading = new SQLStmt(
        "UPSERT INTO SENSOR_READING (tag_name, timestamp, temperature, pressure, quality) " +
        "VALUES (?, ?, ?, ?, ?);"
    );

    public final SQLStmt checkExistingAlert = new SQLStmt(
        "SELECT alert_id, breach_count, severity FROM ALERT " +
        "WHERE tag_name = ? AND status = 'Active' LIMIT 1;"
    );

    public final SQLStmt insertAlert = new SQLStmt(
        "INSERT INTO ALERT (alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count) " +
        "VALUES (?, ?, ?, ?, ?, ?, 'Active', ?, 1);"
    );

    public final SQLStmt updateAlert = new SQLStmt(
        "UPDATE ALERT SET timestamp = ?, breach_count = ?, severity = ? WHERE alert_id = ?;"
    );

    /**
     * Process sensor reading and create/update alert.
     *
     * @param tagName - partition key
     * @param timestamp - sensor reading timestamp
     * @param temperature - temperature value
     * @param pressure - pressure value
     * @param quality - data quality
     * @param alertId - generated alert ID
     * @param nodeId - asset node ID
     * @param title - alert title
     * @param severity - initial severity (High)
     */
    public VoltTable[] run(String tagName, long timestamp, double temperature, double pressure, int quality,
                          long alertId, String nodeId, String title, String severity) {

        // 1. Upsert sensor reading
        voltQueueSQL(upsertReading, tagName, timestamp, temperature, pressure, quality);
        voltExecuteSQL();

        // 2. Check for existing active alert
        voltQueueSQL(checkExistingAlert, tagName);
        VoltTable[] results = voltExecuteSQL();
        VoltTable existing = results[0];

        if (existing.getRowCount() > 0) {
            // Update existing alert
            existing.advanceRow();
            long existingAlertId = existing.getLong("alert_id");
            int breachCount = (int) existing.getLong("breach_count");
            breachCount++;

            // Rule 4: Continuous Condition - Escalate to Critical after 2+ breaches
            if (breachCount >= 2) {
                severity = "Critical";
            }

            voltQueueSQL(updateAlert, timestamp, breachCount, severity, existingAlertId);
            return voltExecuteSQL(true);

        } else {
            // Create new alert
            voltQueueSQL(insertAlert, alertId, nodeId, tagName, title, timestamp, severity, timestamp);
            return voltExecuteSQL(true);
        }
    }
}
