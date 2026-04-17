package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Retrieves all alerts for a specific node.
 *
 * This procedure is partitioned on node_id (first parameter).
 */
public class GetAlertsByNode extends VoltProcedure {

    public final SQLStmt getAllAlerts = new SQLStmt(
        "SELECT alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count " +
        "FROM ALERT WHERE node_id = ? ORDER BY timestamp DESC;"
    );

    public final SQLStmt getActiveAlerts = new SQLStmt(
        "SELECT alert_id, node_id, tag_name, title, timestamp, severity, status, first_breach_time, breach_count " +
        "FROM ALERT WHERE node_id = ? AND status = 'Active' ORDER BY timestamp DESC;"
    );

    /**
     * Get alerts for a node.
     *
     * @param nodeId - partition key (MUST be first parameter)
     * @param activeOnly - if true, return only active alerts
     * @return VoltTable with alerts
     */
    public VoltTable[] run(String nodeId, boolean activeOnly) throws VoltAbortException {
        if (activeOnly) {
            voltQueueSQL(getActiveAlerts, nodeId);
        } else {
            voltQueueSQL(getAllAlerts, nodeId);
        }
        return voltExecuteSQL();
    }
}
