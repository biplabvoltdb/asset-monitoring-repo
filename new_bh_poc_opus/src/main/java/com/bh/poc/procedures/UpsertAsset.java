package com.bh.poc.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

public class UpsertAsset extends VoltProcedure {
    public final SQLStmt sql = new SQLStmt(
        "UPSERT INTO ASSET (NODE_ID, NAME, TYPE, PARENT_ID, LEVEL, TAGS_JSON) VALUES (?,?,?,?,?,?);"
    );
    // Partition key (NODE_ID) must be the FIRST parameter
    public VoltTable[] run(String nodeId, String name, String type,
                           String parentId, int level, String tagsJson) {
        voltQueueSQL(sql, nodeId, name, type, parentId, level, tagsJson);
        return voltExecuteSQL();
    }
}
