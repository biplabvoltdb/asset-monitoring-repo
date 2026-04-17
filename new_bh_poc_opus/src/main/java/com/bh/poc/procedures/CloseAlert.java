package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Rule 5: close all active alerts for a tag when value recovers below threshold. */
public class CloseAlert extends VoltProcedure {
    public final SQLStmt close = new SQLStmt(
        "UPDATE ALERT SET STATUS = 'Closed', TS = ? WHERE TAG_NAME = ? AND STATUS = 'Active';"
    );
    public VoltTable[] run(String tagName, long ts) {
        voltQueueSQL(close, ts, tagName);
        return voltExecuteSQL();
    }
}
