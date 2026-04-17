package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

/**
 * Processes sensor readings and implements:
 * - Rule 1: Temperature Threshold (temp > 50°C → High alert)
 * - Rule 2: Sequential Rule (pressure > 30 AND temp > 50 → High alert)
 * - Rule 4: Continuous Condition (repeated breach → escalate to Critical)
 * - Rule 5: Recovery (temp < threshold → close alert)
 *
 * This procedure is partitioned on tag_name (first parameter).
 */
public class ProcessSensorReading extends VoltProcedure {

    // Insert sensor reading
    public final SQLStmt insertReading = new SQLStmt(
        "INSERT INTO SENSOR_READING (tag_name, timestamp, temperature, pressure, quality) " +
        "VALUES (?, ?, ?, ?, ?);"
    );

    // Get node_id from tag mapping
    public final SQLStmt getNodeId = new SQLStmt(
        "SELECT node_id FROM TAG_ASSET_MAPPING WHERE tag_name = ?;"
    );

    // Check for existing active alert for this tag
    public final SQLStmt checkActiveAlert = new SQLStmt(
        "SELECT alert_id, severity, breach_count, first_breach_time " +
        "FROM ALERT WHERE tag_name = ? AND status = 'Active' ORDER BY alert_id DESC LIMIT 1;"
    );

    // Create new alert
    public final SQLStmt createAlert = new SQLStmt(
        "INSERT INTO ALERT (alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count) " +
        "VALUES (?, ?, ?, ?, ?, ?, 'Active', ?, 1);"
    );

    // Update existing alert (escalate to Critical for continued breach)
    public final SQLStmt updateAlert = new SQLStmt(
        "UPDATE ALERT SET timestamp = ?, breach_count = ?, severity = ? WHERE alert_id = ?;"
    );

    // Close alert (recovery)
    public final SQLStmt closeAlert = new SQLStmt(
        "UPDATE ALERT SET status = 'Closed', timestamp = ? WHERE alert_id = ?;"
    );

    /**
     * Process sensor reading and apply rules.
     *
     * @param tagName - partition key (MUST be first parameter)
     * @param timestamp - reading timestamp in milliseconds
     * @param temperature - temperature value
     * @param pressure - pressure value
     * @param quality - data quality (1=good, 3=questionable)
     * @return VoltTable with processing result
     */
    public VoltTable[] run(String tagName, long timestamp, double temperature, double pressure, int quality) throws VoltAbortException {

        // Insert sensor reading
        voltQueueSQL(insertReading, tagName, timestamp, temperature, pressure, quality);
        voltExecuteSQL();

        // Extract node_id from tag name (format: MachineXXX.Sensor_YYY -> machine-XXX)
        String nodeId = null;
        if (tagName.startsWith("Machine") && tagName.contains(".")) {
            String machineNum = tagName.substring(7, tagName.indexOf('.'));
            nodeId = "machine-" + machineNum;
        } else {
            // Fall back to TAG_ASSET_MAPPING lookup
            voltQueueSQL(getNodeId, tagName);
            VoltTable[] lookupResults = voltExecuteSQL();
            if (lookupResults[0].getRowCount() == 0) {
                return lookupResults;
            }
            nodeId = lookupResults[0].fetchRow(0).getString("node_id");
        }

        // Define thresholds
        final double TEMP_THRESHOLD = 50.0;
        final double PRESSURE_THRESHOLD = 30.0;

        // Check existing alerts
        voltQueueSQL(checkActiveAlert, tagName);
        VoltTable[] alertResults = voltExecuteSQL();
        VoltTable alertTable = alertResults[0];

        boolean hasActiveAlert = alertTable.getRowCount() > 0;

        // Rule 5: Recovery - if temperature is below threshold, close any active alert
        if (temperature < TEMP_THRESHOLD && hasActiveAlert) {
            alertTable.advanceRow();
            long alertId = alertTable.getLong("alert_id");
            voltQueueSQL(closeAlert, timestamp, alertId);
            voltExecuteSQL();
            return new VoltTable[] { createResultTable("RECOVERY", "Alert closed") };
        }

        // Rule 1: Temperature Threshold
        boolean tempBreached = temperature > TEMP_THRESHOLD;

        // Rule 2: Sequential Rule (pressure > 30 AND temp > 50)
        boolean sequentialBreached = (pressure > PRESSURE_THRESHOLD) && (temperature > TEMP_THRESHOLD);

        if (!tempBreached && !sequentialBreached) {
            // No breach - return
            return new VoltTable[] { createResultTable("NO_BREACH", "Temperature and pressure normal") };
        }

        String alertTitle;
        String severity = "High";

        if (sequentialBreached) {
            alertTitle = "Sequential Rule Breach: High Pressure and Temperature";
        } else {
            alertTitle = "Temperature Threshold Breach";
        }

        // Rule 4: Continuous Condition
        if (hasActiveAlert) {
            // Existing active alert - update and escalate to Critical
            alertTable.advanceRow();
            long alertId = alertTable.getLong("alert_id");
            int breachCount = (int) alertTable.getLong("breach_count");

            // Escalate to Critical on continued breach
            voltQueueSQL(updateAlert, timestamp, breachCount + 1, "Critical", alertId);
            voltExecuteSQL();
            return new VoltTable[] { createResultTable("ESCALATED", "Alert escalated to Critical") };
        } else {
            // First breach - create new alert
            // Generate alert ID from timestamp + hash
            long alertId = timestamp * 1000 + Math.abs(tagName.hashCode() % 1000);
            voltQueueSQL(createAlert, alertId, nodeId, tagName, alertTitle, timestamp, severity, timestamp);
            voltExecuteSQL();
            return new VoltTable[] { createResultTable("ALERT_CREATED", "New " + severity + " alert created") };
        }
    }

    private VoltTable createResultTable(String status, String message) {
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("status", VoltType.STRING),
            new VoltTable.ColumnInfo("message", VoltType.STRING)
        );
        result.addRow(status, message);
        return result;
    }
}
