package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Rule 2 helper: get the most recent temperature sample for a tag from the rolling window. */
public class GetLatestTemp extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "SELECT TEMP FROM SENSOR_WINDOW WHERE TAG_NAME = ? ORDER BY TS DESC LIMIT 1;"
    );
    public VoltTable[] run(String tagName) {
        voltQueueSQL(sql, tagName);
        return voltExecuteSQL();
    }
}
