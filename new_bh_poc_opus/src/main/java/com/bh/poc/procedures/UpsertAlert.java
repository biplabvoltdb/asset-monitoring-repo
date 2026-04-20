package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Write a new alert (or upsert existing one keyed by (TAG_NAME, ALERT_ID)). */
public class UpsertAlert extends VoltProcedure {
    public final SQLStmt upsertAlert = new SQLStmt(
        "UPSERT INTO ALERT (TAG_NAME, ALERT_ID, NODE_ID, TITLE, SEVERITY, STATUS, RULE_ID, TS, VALUE) " +
        "VALUES (?,?,?,?,?,?,?,?,?);"
    );

    // Partition key (TAG_NAME) first
    public VoltTable[] run(String tagName, String alertId, String nodeId, String title,
                           String severity, String status, String ruleId, long ts, double value) {
        voltQueueSQL(upsertAlert, tagName, alertId, nodeId, title, severity, status, ruleId, ts, value);
        return voltExecuteSQL(true);
    }
}
