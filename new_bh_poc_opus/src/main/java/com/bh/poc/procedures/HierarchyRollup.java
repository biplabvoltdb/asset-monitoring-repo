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
        // BFS across descendants, batched by level to minimize voltExecuteSQL() calls.
        java.util.List<String> level = new java.util.ArrayList<>();
        level.add(rootNodeId);
        long total = 0, active = 0;
        while (!level.isEmpty()) {
            for (String n : level) {
                voltQueueSQL(selfRollup, n);
                voltQueueSQL(children, n);
            }
            VoltTable[] r = voltExecuteSQL();
            java.util.List<String> nextLevel = new java.util.ArrayList<>();
            for (int i = 0; i < level.size(); i++) {
                VoltTable rollup = r[i * 2];
                VoltTable kids = r[i * 2 + 1];
                if (rollup.advanceRow()) { total += rollup.getLong(0); active += rollup.getLong(1); }
                while (kids.advanceRow()) nextLevel.add(kids.getString(0));
            }
            level = nextLevel;
        }
        VoltTable out = new VoltTable(
            new VoltTable.ColumnInfo("NODE_ID", org.voltdb.VoltType.STRING),
            new VoltTable.ColumnInfo("TOTAL_ALERTS", org.voltdb.VoltType.BIGINT),
            new VoltTable.ColumnInfo("ACTIVE_ALERTS", org.voltdb.VoltType.BIGINT));
        out.addRow(rootNodeId, total, active);
        return new VoltTable[] { out };
    }
}
