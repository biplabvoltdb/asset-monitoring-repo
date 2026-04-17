package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Fast-path procedure for high-throughput sensor reading ingestion.
 * Only inserts data without alert processing.
 * Partitioned on tag_name for maximum throughput.
 */
public class InsertSensorReading extends VoltProcedure {

    public final SQLStmt upsertReading = new SQLStmt(
        "UPSERT INTO SENSOR_READING (tag_name, timestamp, temperature, pressure, quality) " +
        "VALUES (?, ?, ?, ?, ?);"
    );

    /**
     * Fast insert of sensor reading.
     * @param tagName - partition key (MUST be first parameter)
     * @param timestamp - reading timestamp in milliseconds
     * @param temperature - temperature value
     * @param pressure - pressure value
     * @param quality - data quality (1=good, 3=questionable)
     * @return VoltTable with insert result
     */
    public VoltTable[] run(String tagName, long timestamp, double temperature, double pressure, int quality) {
        voltQueueSQL(upsertReading, tagName, timestamp, temperature, pressure, quality);
        return voltExecuteSQL(true); // true = return last statement result
    }
}
