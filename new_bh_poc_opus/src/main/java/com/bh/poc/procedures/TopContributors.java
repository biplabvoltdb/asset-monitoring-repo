package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

/** Real-Time Analytics 2 - top-X direct children of a node by total alert count. */
public class TopContributors extends VoltProcedure {
    public final SQLStmt children = new SQLStmt("SELECT NODE_ID FROM ASSET WHERE PARENT_ID = ?;");
    public final SQLStmt selfRollup = new SQLStmt(
        "SELECT TOTAL_ALERTS, ACTIVE_ALERTS FROM HIERARCHY_ROLLUP WHERE NODE_ID = ?;");

    public VoltTable[] run(String parentNodeId, int topX) {
        voltQueueSQL(children, parentNodeId);
        VoltTable[] r0 = voltExecuteSQL();
        java.util.List<String> kids = new java.util.ArrayList<>();
        while (r0[0].advanceRow()) kids.add(r0[0].getString(0));

        for (String k : kids) voltQueueSQL(selfRollup, k);
        VoltTable[] rs = voltExecuteSQL();

        java.util.List<long[]> rows = new java.util.ArrayList<>(); // idx -> [total,active]
        java.util.List<String> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < rs.length; i++) {
            long total = 0, active = 0;
            if (rs[i].advanceRow()) { total = rs[i].getLong(0); active = rs[i].getLong(1); }
            rows.add(new long[]{ total, active });
            nodes.add(kids.get(i));
        }
        // Sort by total desc
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) idx.add(i);
        idx.sort((a,b) -> Long.compare(rows.get(b)[0], rows.get(a)[0]));

        VoltTable out = new VoltTable(
            new VoltTable.ColumnInfo("NODE_ID", VoltType.STRING),
            new VoltTable.ColumnInfo("TOTAL_ALERTS", VoltType.BIGINT),
            new VoltTable.ColumnInfo("ACTIVE_ALERTS", VoltType.BIGINT));
        int limit = Math.min(topX, idx.size());
        for (int i = 0; i < limit; i++) {
            int j = idx.get(i);
            out.addRow(nodes.get(j), rows.get(j)[0], rows.get(j)[1]);
        }
        return new VoltTable[] { out };
    }
}
