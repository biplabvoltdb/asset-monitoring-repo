package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Real-Time Analytics 1 - for a given root NODE_ID compute total and active alert counts
 * across all descendants. Implementation: walks the ASSET tree breadth-first using
 * PARENT_ID index (multi-partition). For 1.5M assets you would materialise this; here we
 * expose the query path for the PoC.
 */
public class HierarchyRollup extends VoltProcedure {
    public final SQLStmt children = new SQLStmt("SELECT NODE_ID FROM ASSET WHERE PARENT_ID = ?;");
    public final SQLStmt selfRollup = new SQLStmt(
        "SELECT TOTAL_ALERTS, ACTIVE_ALERTS FROM HIERARCHY_ROLLUP WHERE NODE_ID = ?;");

    public VoltTable[] run(String rootNodeId) {
        // BFS across descendants (PoC implementation - for large fan-out, materialise rollups).
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.add(rootNodeId);
        long total = 0, active = 0;
        while (!q.isEmpty()) {
            String n = q.poll();
            voltQueueSQL(selfRollup, n);
            voltQueueSQL(children, n);
            VoltTable[] r = voltExecuteSQL();
            if (r[0].advanceRow()) { total += r[0].getLong(0); active += r[0].getLong(1); }
            while (r[1].advanceRow()) q.add(r[1].getString(0));
        }
        VoltTable out = new VoltTable(
            new VoltTable.ColumnInfo("NODE_ID", org.voltdb.VoltType.STRING),
            new VoltTable.ColumnInfo("TOTAL_ALERTS", org.voltdb.VoltType.BIGINT),
            new VoltTable.ColumnInfo("ACTIVE_ALERTS", org.voltdb.VoltType.BIGINT));
        out.addRow(rootNodeId, total, active);
        return new VoltTable[] { out };
    }
}
