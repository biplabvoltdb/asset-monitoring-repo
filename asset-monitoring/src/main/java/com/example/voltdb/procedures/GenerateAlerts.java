package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

/**
 * Batch alert generation procedure.
 * Analyzes recent sensor readings and generates alerts based on rules.
 *
 * Should be called periodically (e.g., every 5-10 seconds) to process recent data.
 */
public class GenerateAlerts extends VoltProcedure {

    // Get recent sensor readings that exceeded thresholds (last 30 seconds)
    public final SQLStmt getRecentBreaches = new SQLStmt(
        "SELECT tag_name, timestamp, temperature, pressure " +
        "FROM SENSOR_READING " +
        "WHERE timestamp > ? AND (temperature > 50.0 OR (pressure > 30.0 AND temperature > 50.0)) " +
        "ORDER BY tag_name, timestamp;"
    );

    // Check for existing active alert
    public final SQLStmt checkActiveAlert = new SQLStmt(
        "SELECT alert_id, severity, breach_count, first_breach_time " +
        "FROM ALERT WHERE tag_name = ? AND status = 'Active' LIMIT 1;"
    );

    // Create new alert
    public final SQLStmt createAlert = new SQLStmt(
        "INSERT INTO ALERT (alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count) " +
        "VALUES (?, ?, ?, ?, ?, ?, 'Active', ?, 1);"
    );

    // Update existing alert (escalate or update breach count)
    public final SQLStmt updateAlert = new SQLStmt(
        "UPDATE ALERT SET timestamp = ?, breach_count = ?, severity = ? WHERE alert_id = ?;"
    );

    // Close alert (recovery)
    public final SQLStmt closeAlert = new SQLStmt(
        "UPDATE ALERT SET status = 'Closed', timestamp = ? WHERE tag_name = ? AND status = 'Active';"
    );

    /**
     * Generate alerts based on recent sensor readings.
     * @param lookbackMs - how far back to look for breaches (e.g., 30000 for 30 seconds)
     * @return VoltTable with alert generation statistics
     */
    public VoltTable[] run(long lookbackMs) throws VoltAbortException {
        long currentTime = System.currentTimeMillis();
        long sinceTime = currentTime - lookbackMs;

        // Get recent threshold breaches
        voltQueueSQL(getRecentBreaches, sinceTime);
        VoltTable[] results = voltExecuteSQL();
        VoltTable breaches = results[0];

        int alertsCreated = 0;
        int alertsEscalated = 0;
        int alertsClosed = 0;

        // Process each breach
        String lastTagName = "";
        while (breaches.advanceRow()) {
            String tagName = breaches.getString("tag_name");
            long timestamp = breaches.getLong("timestamp");
            double temperature = breaches.getDouble("temperature");
            double pressure = breaches.getDouble("pressure");

            // Skip duplicate processing for same sensor
            if (tagName.equals(lastTagName)) {
                continue;
            }
            lastTagName = tagName;

            // Extract node_id from tag name (Machine001.Sensor_XXX -> machine-001)
            String nodeId = extractNodeId(tagName);
            if (nodeId == null) {
                continue;
            }

            // Check for existing active alert
            voltQueueSQL(checkActiveAlert, tagName);
            VoltTable[] alertCheck = voltExecuteSQL();
            VoltTable existingAlert = alertCheck[0];

            // Determine severity
            String severity = "High";
            String title = "Temperature Threshold Breach";
            if (temperature > 50.0 && pressure > 30.0) {
                title = "Combined Temperature & Pressure Breach";
            }

            if (existingAlert.getRowCount() > 0) {
                // Update existing alert
                existingAlert.advanceRow();
                long alertId = existingAlert.getLong("alert_id");
                int breachCount = (int) existingAlert.getLong("breach_count");
                breachCount++;

                // Rule 4: Escalate to Critical after 2+ breaches
                if (breachCount >= 2) {
                    severity = "Critical";
                    alertsEscalated++;
                }

                voltQueueSQL(updateAlert, timestamp, breachCount, severity, alertId);
                voltExecuteSQL();

            } else {
                // Create new alert
                long alertId = timestamp * 1000 + Math.abs(tagName.hashCode() % 1000);
                voltQueueSQL(createAlert, alertId, nodeId, tagName, title, timestamp, severity, timestamp);
                voltExecuteSQL();
                alertsCreated++;
            }
        }

        // Create result table
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("alerts_created", VoltType.INTEGER),
            new VoltTable.ColumnInfo("alerts_escalated", VoltType.INTEGER),
            new VoltTable.ColumnInfo("breaches_analyzed", VoltType.BIGINT)
        );
        result.addRow(alertsCreated, alertsEscalated, breaches.getRowCount());

        return new VoltTable[] { result };
    }

    private String extractNodeId(String tagName) {
        if (tagName.startsWith("Machine") && tagName.contains(".")) {
            String machineNum = tagName.substring(7, tagName.indexOf('.'));
            return "machine-" + machineNum;
        }
        return null;
    }
}
