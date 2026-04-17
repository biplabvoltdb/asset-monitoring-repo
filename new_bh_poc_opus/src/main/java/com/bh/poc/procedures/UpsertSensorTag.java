package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

public class UpsertSensorTag extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "UPSERT INTO SENSOR_TAG (TAG_NAME, NODE_ID, METRIC, TEMP_THRESHOLD, PRESSURE_THRESHOLD, TEMP_HIGH) VALUES (?,?,?,?,?,?);"
    );
    // Partition key (TAG_NAME) first
    public VoltTable[] run(String tagName, String nodeId, String metric,
                           double tempThreshold, double pressureThreshold, double tempHigh) {
        voltQueueSQL(sql, tagName, nodeId, metric, tempThreshold, pressureThreshold, tempHigh);
        return voltExecuteSQL();
    }
}
