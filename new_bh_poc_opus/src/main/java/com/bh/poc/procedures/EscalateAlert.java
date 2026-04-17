package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Rule 4 continued breach: update TS and escalate severity to Critical. */
public class EscalateAlert extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "UPDATE ALERT SET SEVERITY = 'Critical', TS = ?, VALUE = ? WHERE TAG_NAME = ? AND ALERT_ID = ?;"
    );
    public VoltTable[] run(String tagName, String alertId, long ts, double value) {
        voltQueueSQL(sql, ts, value, tagName, alertId);
        return voltExecuteSQL();
    }
}
