package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Retrieves the asset hierarchy.
 *
 * Multi-partition procedure (accesses all nodes).
 */
public class GetAssetHierarchy extends VoltProcedure {

    public final SQLStmt getHierarchy = new SQLStmt(
        "SELECT node_id, name, type, parent FROM ASSET ORDER BY parent NULLS FIRST, name;"
    );

    /**
     * Get the complete asset hierarchy.
     *
     * @return VoltTable with asset hierarchy
     */
    public VoltTable[] run() throws VoltAbortException {
        voltQueueSQL(getHierarchy);
        return voltExecuteSQL();
    }
}
