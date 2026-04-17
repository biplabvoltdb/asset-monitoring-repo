package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Rule 4: Continuous Condition - Escalate Alert
 *
 * Updates an existing alert with new timestamp and escalates severity to Critical
 * on continued breach.
 *
 * This procedure is partitioned on node_id (first parameter).
 */
public class EscalateAlert extends VoltProcedure {

    public final SQLStmt escalateAlert = new SQLStmt(
        "UPDATE ALERT SET timestamp = ?, breach_count = breach_count + 1, severity = 'Critical' " +
        "WHERE alert_id = ? AND node_id = ?;"
    );

    public final SQLStmt getAlert = new SQLStmt(
        "SELECT alert_id, severity, breach_count FROM ALERT WHERE alert_id = ? AND node_id = ?;"
    );

    /**
     * Escalate an existing alert to Critical severity.
     *
     * @param nodeId - partition key (MUST be first parameter)
     * @param alertId - alert to escalate
     * @param timestamp - new timestamp
     * @return VoltTable with result
     */
    public VoltTable[] run(String nodeId, long alertId, long timestamp) throws VoltAbortException {
        voltQueueSQL(escalateAlert, timestamp, alertId, nodeId);
        voltQueueSQL(getAlert, alertId, nodeId);
        return voltExecuteSQL();
    }
}
