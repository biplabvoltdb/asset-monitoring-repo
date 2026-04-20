package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Increment rollup counters for a node. Partitioned on NODE_ID (HIERARCHY_ROLLUP). */
public class UpdateRollup extends VoltProcedure {
    public final SQLStmt getRollup = new SQLStmt(
        "SELECT TOTAL_ALERTS, ACTIVE_ALERTS FROM HIERARCHY_ROLLUP WHERE NODE_ID = ?;"
    );
    public final SQLStmt upsertRollup = new SQLStmt(
        "UPSERT INTO HIERARCHY_ROLLUP (NODE_ID, TOTAL_ALERTS, ACTIVE_ALERTS, UPDATED_TS) VALUES (?,?,?,?);"
    );

    // Partition key (NODE_ID) first
    public VoltTable[] run(String nodeId, String status, long ts) {
        voltQueueSQL(getRollup, nodeId);
        VoltTable[] r = voltExecuteSQL();
        long total = 0, active = 0;
        if (r[0].advanceRow()) { total = r[0].getLong(0); active = r[0].getLong(1); }
        voltQueueSQL(upsertRollup, nodeId, total + 1, active + ("Active".equals(status) ? 1 : 0), ts);
        return voltExecuteSQL(true);
    }
}
