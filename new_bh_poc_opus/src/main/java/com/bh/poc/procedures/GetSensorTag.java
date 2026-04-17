package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Metadata / threshold lookup called by the VoltSP processor via the voltdb-client resource. */
public class GetSensorTag extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "SELECT TAG_NAME, NODE_ID, METRIC, TEMP_THRESHOLD, PRESSURE_THRESHOLD, TEMP_HIGH " +
        "FROM SENSOR_TAG WHERE TAG_NAME = ?;"
    );
    public VoltTable[] run(String tagName) {
        voltQueueSQL(sql, tagName);
        return voltExecuteSQL();
    }
}
