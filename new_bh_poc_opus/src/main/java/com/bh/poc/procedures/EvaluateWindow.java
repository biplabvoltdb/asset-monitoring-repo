package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Rule 3 evaluation: over the last 10 minutes for this tag, return the fraction of samples
 * for which (TEMP > tempThreshold AND PRESSURE > pressureThreshold). The processor decides
 * whether to emit a Medium-severity alert based on this fraction.
 */
public class EvaluateWindow extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "SELECT " +
        "  COUNT(*) AS N, " +
        "  SUM(CASE WHEN TEMP > ? AND PRESSURE > ? THEN 1 ELSE 0 END) AS HITS " +
        "FROM SENSOR_WINDOW WHERE TAG_NAME = ? AND TS >= ?;"
    );
    public VoltTable[] run(String tagName, long nowMs, double tempThreshold, double pressureThreshold) {
        voltQueueSQL(sql, tempThreshold, pressureThreshold, tagName, nowMs - 600_000L);
        return voltExecuteSQL();
    }
}
