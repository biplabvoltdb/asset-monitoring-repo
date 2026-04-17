package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Registers a tag to asset mapping in the TAG_ASSET_MAPPING table.
 *
 * This is a replicated table, so this is a multi-partition procedure.
 */
public class RegisterTagMapping extends VoltProcedure {

    public final SQLStmt insertMapping = new SQLStmt(
        "UPSERT INTO TAG_ASSET_MAPPING (tag_name, node_id) VALUES (?, ?);"
    );

    /**
     * Register a tag to asset mapping.
     *
     * @param tagName - sensor tag name (e.g., "Asset1.Compressor_Sensor_01")
     * @param nodeId - asset node_id
     * @return VoltTable with result
     */
    public VoltTable[] run(String tagName, String nodeId) throws VoltAbortException {
        voltQueueSQL(insertMapping, tagName, nodeId);
        return voltExecuteSQL();
    }
}
