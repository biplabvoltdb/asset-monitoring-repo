package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Write a new alert (or upsert existing one keyed by (TAG_NAME, ALERT_ID)). Also bumps rollup counters. */
public class UpsertAlert extends VoltProcedure {
    public final SQLStmt upsertAlert = new SQLStmt(
        "UPSERT INTO ALERT (TAG_NAME, ALERT_ID, NODE_ID, TITLE, SEVERITY, STATUS, RULE_ID, TS, VALUE) " +
        "VALUES (?,?,?,?,?,?,?,?,?);"
    );
    public final SQLStmt upsertRollup = new SQLStmt(
        "UPSERT INTO HIERARCHY_ROLLUP (NODE_ID, TOTAL_ALERTS, ACTIVE_ALERTS, UPDATED_TS) VALUES (?,?,?,?);"
    );
    public final SQLStmt getRollup = new SQLStmt(
        "SELECT TOTAL_ALERTS, ACTIVE_ALERTS FROM HIERARCHY_ROLLUP WHERE NODE_ID = ?;"
    );

    // Partition key (TAG_NAME) first
    public VoltTable[] run(String tagName, String alertId, String nodeId, String title,
                           String severity, String status, String ruleId, long ts, double value) {
        voltQueueSQL(upsertAlert, tagName, alertId, nodeId, title, severity, status, ruleId, ts, value);
        // NOTE: rollup is best-effort co-partitioned write; multi-partition rollup is also exposed via HierarchyRollup procedure
        voltQueueSQL(getRollup, nodeId);
        VoltTable[] r = voltExecuteSQL();
        long total = 0, active = 0;
        if (r[1].advanceRow()) { total = r[1].getLong(0); active = r[1].getLong(1); }
        voltQueueSQL(upsertRollup, nodeId, total + 1, active + ("Active".equals(status) ? 1 : 0), ts);
        return voltExecuteSQL(true);
    }
}
