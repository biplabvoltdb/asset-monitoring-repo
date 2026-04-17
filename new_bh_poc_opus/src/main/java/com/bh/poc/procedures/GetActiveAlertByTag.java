package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Look up the most recent Active alert for a tag, used by Rule 4/5. */
public class GetActiveAlertByTag extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "SELECT ALERT_ID, SEVERITY, STATUS, RULE_ID, TS FROM ALERT " +
        "WHERE TAG_NAME = ? AND STATUS = 'Active' ORDER BY TS DESC LIMIT 1;"
    );
    public VoltTable[] run(String tagName) {
        voltQueueSQL(sql, tagName);
        return voltExecuteSQL();
    }
}
