package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Append one sample to the rolling-window table and prune entries older than 10 minutes. */
public class AppendWindowSample extends VoltProcedure {
    public final SQLStmt insert = new SQLStmt(
        "UPSERT INTO SENSOR_WINDOW (TAG_NAME, TS, TEMP, PRESSURE) VALUES (?,?,?,?);"
    );
    public final SQLStmt prune = new SQLStmt(
        "DELETE FROM SENSOR_WINDOW WHERE TAG_NAME = ? AND TS < ?;"
    );
    public VoltTable[] run(String tagName, long ts, double temp, double pressure) {
        voltQueueSQL(insert, tagName, ts, temp, pressure);
        voltQueueSQL(prune,  tagName, ts - 600_000L); // 10-minute window
        return voltExecuteSQL();
    }
}
