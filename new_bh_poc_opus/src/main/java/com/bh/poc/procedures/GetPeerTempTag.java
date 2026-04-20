package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/** Rule 2 helper: find the TEMPERATURE tag for a given node. Multi-partition (SENSOR_TAG partitioned on TAG_NAME, not NODE_ID). */
public class GetPeerTempTag extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "SELECT TAG_NAME FROM SENSOR_TAG WHERE NODE_ID = ? AND METRIC = 'TEMPERATURE';"
    );
    public VoltTable[] run(String nodeId) {
        voltQueueSQL(sql, nodeId);
        return voltExecuteSQL();
    }
}
