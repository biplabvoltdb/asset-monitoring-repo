package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * Rule 7: Top Contributors
 *
 * Identifies top X child nodes contributing most alerts to total count.
 * This is a multi-partition procedure.
 *
 * Multi-partition procedure (accesses all nodes).
 */
public class GetTopContributors extends VoltProcedure {

    public final SQLStmt getTopByTotalAlerts = new SQLStmt(
        "SELECT hm.node_id, a.name, a.type, a.parent, hm.total_alerts, hm.total_active_alerts " +
        "FROM HIERARCHICAL_METRICS hm " +
        "JOIN ASSET a ON hm.node_id = a.node_id " +
        "WHERE a.parent = ? " +
        "ORDER BY hm.total_alerts DESC " +
        "LIMIT ?;"
    );

    public final SQLStmt getTopByActiveAlerts = new SQLStmt(
        "SELECT hm.node_id, a.name, a.type, a.parent, hm.total_alerts, hm.total_active_alerts " +
        "FROM HIERARCHICAL_METRICS hm " +
        "JOIN ASSET a ON hm.node_id = a.node_id " +
        "WHERE a.parent = ? " +
        "ORDER BY hm.total_active_alerts DESC " +
        "LIMIT ?;"
    );

    /**
     * Get top contributors under a parent node.
     *
     * @param parentNodeId - parent node to find top contributors for (null for root level)
     * @param topN - number of top contributors to return
     * @param byActiveAlerts - if true, sort by active alerts; if false, sort by total alerts
     * @return VoltTable with top contributors
     */
    public VoltTable[] run(String parentNodeId, int topN, boolean byActiveAlerts) throws VoltAbortException {

        if (byActiveAlerts) {
            voltQueueSQL(getTopByActiveAlerts, parentNodeId, topN);
        } else {
            voltQueueSQL(getTopByTotalAlerts, parentNodeId, topN);
        }

        return voltExecuteSQL();
    }
}
