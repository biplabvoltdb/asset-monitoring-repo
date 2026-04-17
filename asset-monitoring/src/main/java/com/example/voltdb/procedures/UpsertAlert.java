package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Upsert alert - creates new alert or updates existing one.
 * Partitioned on node_id for co-location with asset data.
 */
public class UpsertAlert extends VoltProcedure {

    public final SQLStmt checkExisting = new SQLStmt(
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
     * Upsert alert with escalation logic.
     * @param nodeId - partition key (MUST be first parameter)
     * @param alertId - generated alert ID
     * @param tagName - sensor tag name
     * @param title - alert title
     * @param timestamp - alert timestamp
     * @param severity - initial severity
     */
    public VoltTable[] run(String nodeId, long alertId, String tagName, String title, long timestamp, String severity) {

        // Check for existing active alert for this tag
        voltQueueSQL(checkExisting, tagName);
        VoltTable[] results = voltExecuteSQL();
        VoltTable existing = results[0];

        if (existing.getRowCount() > 0) {
            // Update existing alert
            existing.advanceRow();
            long existingAlertId = existing.getLong("alert_id");
            int breachCount = (int) existing.getLong("breach_count");
            breachCount++;

            // Rule 4: Escalate to Critical after 2+ breaches
            if (breachCount >= 2) {
                severity = "Critical";
            }

            voltQueueSQL(updateAlert, timestamp, breachCount, severity, existingAlertId);
            return voltExecuteSQL(true);

        } else {
            // Insert new alert
            voltQueueSQL(insertAlert, alertId, nodeId, tagName, title, timestamp, severity, timestamp);
            return voltExecuteSQL(true);
        }
    }
}
