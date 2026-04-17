package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Upserts an asset into the ASSET table.
 *
 * This procedure is partitioned on node_id (first parameter).
 */
public class UpsertAsset extends VoltProcedure {

    public final SQLStmt upsertAsset = new SQLStmt(
        "UPSERT INTO ASSET (node_id, name, type, parent, tags) VALUES (?, ?, ?, ?, ?);"
    );

    /**
     * Upsert an asset.
     *
     * @param nodeId - partition key (MUST be first parameter)
     * @param name - asset name
     * @param type - asset type (Fleet, Machine, etc.)
     * @param parent - parent node_id (null for root)
     * @param tags - JSON string with thresholds and metadata
     * @return VoltTable with result
     */
    public VoltTable[] run(String nodeId, String name, String type, String parent, String tags) throws VoltAbortException {
        voltQueueSQL(upsertAsset, nodeId, name, type, parent, tags);
        return voltExecuteSQL();
    }
}
