package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

/**
 * Rule 6: Hierarchical Roll-Up
 *
 * Computes total alerts and total active alerts across descendants for each node
 * in the asset hierarchy. This is a multi-partition procedure as it needs to
 * aggregate data across the entire tree.
 *
 * Multi-partition procedure (accesses all nodes).
 */
public class ComputeHierarchicalMetrics extends VoltProcedure {

    // Get all assets to build hierarchy
    public final SQLStmt getAllAssets = new SQLStmt(
        "SELECT node_id, parent FROM ASSET ORDER BY parent NULLS FIRST;"
    );

    // Get alert counts per node
    public final SQLStmt getAlertCounts = new SQLStmt(
        "SELECT node_id, COUNT(*) as total_alerts, " +
        "SUM(CASE WHEN status = 'Active' THEN 1 ELSE 0 END) as active_alerts " +
        "FROM ALERT GROUP BY node_id;"
    );

    // Upsert hierarchical metrics
    public final SQLStmt upsertMetrics = new SQLStmt(
        "UPSERT INTO HIERARCHICAL_METRICS " +
        "(node_id, total_alerts, total_active_alerts, last_updated) " +
        "VALUES (?, ?, ?, ?);"
    );

    /**
     * Compute hierarchical metrics for all nodes.
     * Rolls up alert counts from leaf nodes to root.
     *
     * @return VoltTable with computed metrics
     */
    public VoltTable[] run() throws VoltAbortException {

        long currentTime = System.currentTimeMillis();

        // Get all assets and alert counts
        voltQueueSQL(getAllAssets);
        voltQueueSQL(getAlertCounts);
        VoltTable[] results = voltExecuteSQL();

        VoltTable assets = results[0];
        VoltTable alertCounts = results[1];

        // Build map of direct alert counts
        java.util.Map<String, long[]> directCounts = new java.util.HashMap<>();
        while (alertCounts.advanceRow()) {
            String nodeId = alertCounts.getString("node_id");
            long totalAlerts = alertCounts.getLong("total_alerts");
            long activeAlerts = alertCounts.getLong("active_alerts");
            directCounts.put(nodeId, new long[]{totalAlerts, activeAlerts});
        }

        // Build hierarchy map (node -> parent)
        java.util.Map<String, String> hierarchy = new java.util.HashMap<>();
        java.util.Set<String> allNodes = new java.util.HashSet<>();

        while (assets.advanceRow()) {
            String nodeId = assets.getString("node_id");
            String parent = assets.getString("parent");
            allNodes.add(nodeId);
            if (parent != null && !parent.isEmpty()) {
                hierarchy.put(nodeId, parent);
            }
        }

        // Compute rollup counts (including descendants)
        java.util.Map<String, long[]> rollupCounts = new java.util.HashMap<>();

        for (String nodeId : allNodes) {
            long[] counts = computeSubtreeCounts(nodeId, hierarchy, directCounts, rollupCounts);
            rollupCounts.put(nodeId, counts);
        }

        // Store metrics for all nodes
        for (String nodeId : allNodes) {
            long[] counts = rollupCounts.get(nodeId);
            voltQueueSQL(upsertMetrics, nodeId, counts[0], counts[1], currentTime);
        }

        voltExecuteSQL();

        // Return summary
        VoltTable summary = new VoltTable(
            new VoltTable.ColumnInfo("nodes_processed", VoltType.INTEGER),
            new VoltTable.ColumnInfo("timestamp", VoltType.BIGINT)
        );
        summary.addRow(allNodes.size(), currentTime);

        return new VoltTable[] { summary };
    }

    /**
     * Recursively compute total and active alert counts for a node and all its descendants.
     */
    private long[] computeSubtreeCounts(String nodeId,
                                       java.util.Map<String, String> hierarchy,
                                       java.util.Map<String, long[]> directCounts,
                                       java.util.Map<String, long[]> cache) {

        // Check cache
        if (cache.containsKey(nodeId)) {
            return cache.get(nodeId);
        }

        // Start with direct counts
        long[] counts = directCounts.getOrDefault(nodeId, new long[]{0, 0});
        long totalAlerts = counts[0];
        long activeAlerts = counts[1];

        // Add counts from all children
        for (java.util.Map.Entry<String, String> entry : hierarchy.entrySet()) {
            String childId = entry.getKey();
            String parentId = entry.getValue();

            if (nodeId.equals(parentId)) {
                // This is a child of current node
                long[] childCounts = computeSubtreeCounts(childId, hierarchy, directCounts, cache);
                totalAlerts += childCounts[0];
                activeAlerts += childCounts[1];
            }
        }

        long[] result = new long[]{totalAlerts, activeAlerts};
        cache.put(nodeId, result);
        return result;
    }
}
