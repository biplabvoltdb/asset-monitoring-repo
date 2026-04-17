package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Rule 5: Recovery
 *
 * Closes active alerts when temperature returns below threshold.
 *
 * This procedure is partitioned on node_id (first parameter).
 */
public class RecoverAlert extends VoltProcedure {

    public final SQLStmt closeAlert = new SQLStmt(
        "UPDATE ALERT SET status = 'Closed', timestamp = ? " +
        "WHERE node_id = ? AND tag_name = ? AND status = 'Active';"
    );

    public final SQLStmt getClosedAlerts = new SQLStmt(
        "SELECT alert_id, title, severity FROM ALERT " +
        "WHERE node_id = ? AND tag_name = ? AND status = 'Closed';"
    );

    /**
     * Close active alerts for a tag when conditions return to normal.
     *
     * @param nodeId - partition key (MUST be first parameter)
     * @param tagName - tag name for the sensor
     * @param timestamp - recovery timestamp
     * @return VoltTable with closed alerts
     */
    public VoltTable[] run(String nodeId, String tagName, long timestamp) throws VoltAbortException {
        voltQueueSQL(closeAlert, timestamp, nodeId, tagName);
        voltQueueSQL(getClosedAlerts, nodeId, tagName);
        return voltExecuteSQL();
    }
}
