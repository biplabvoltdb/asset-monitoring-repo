package com.example.voltdb;

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Asset Monitoring VoltDB client application.
 * Processes sensor data streams and applies 7 alerting rules.
 */
public class AssetMonitoringApp {

    private final Client2 client;
    private final ObjectMapper jsonMapper;

    public AssetMonitoringApp(Client2 client) {
        this.client = client;
        this.jsonMapper = new ObjectMapper();
    }

    // ========================================
    // Asset Management
    // ========================================

    public void upsertAsset(String nodeId, String name, String type, String parent, String tags) throws Exception {
        client.callProcedureAsync("UpsertAsset", nodeId, name, type, parent, tags)
            .thenApply(response -> checkResponse("UpsertAsset", response))
            .get();
    }

    public void registerTagMapping(String tagName, String nodeId) throws Exception {
        client.callProcedureAsync("RegisterTagMapping", tagName, nodeId)
            .thenApply(response -> checkResponse("RegisterTagMapping", response))
            .get();
    }

    public VoltTable getAssetHierarchy() throws Exception {
        return client.callProcedureAsync("GetAssetHierarchy")
            .thenApply(response -> checkResponse("GetAssetHierarchy", response).getResults()[0])
            .get();
    }

    // ========================================
    // Sensor Reading Processing (Rules 1, 2, 4, 5)
    // ========================================

    /**
     * Process sensor reading and apply rules:
     * - Rule 1: Temperature Threshold
     * - Rule 2: Sequential Rule (pressure + temp)
     * - Rule 4: Continuous Condition (escalation)
     * - Rule 5: Recovery
     */
    public VoltTable processSensorReading(String tagName, long timestamp, double temperature, double pressure, int quality) throws Exception {
        return client.callProcedureAsync("ProcessSensorReading", tagName, timestamp, temperature, pressure, quality)
            .thenApply(response -> checkResponse("ProcessSensorReading", response).getResults()[0])
            .get();
    }

    /**
     * Process JSON input from the sample format.
     * Sample Input:
     * {
     *   "tags": [
     *     {
     *       "name": "Asset1.Compressor_Sensor_01",
     *       "results": [
     *         {
     *           "values": [
     *             [1677840000000, 150.5, 1],
     *             [1677840060000, 151.2, 3]
     *           ]
     *         }
     *       ]
     *     }
     *   ]
     * }
     */
    public void processJsonInput(String jsonInput) throws Exception {
        JsonNode root = jsonMapper.readTree(jsonInput);
        JsonNode tags = root.get("tags");

        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String tagName = tag.get("name").asText();
                JsonNode results = tag.get("results");

                if (results != null && results.isArray()) {
                    for (JsonNode result : results) {
                        JsonNode values = result.get("values");

                        if (values != null && values.isArray()) {
                            for (JsonNode value : values) {
                                long timestamp = value.get(0).asLong();
                                double temperature = value.get(1).asDouble();
                                int quality = value.get(2).asInt();
                                double pressure = 0.0; // Default, can be added to input

                                VoltTable processResult = processSensorReading(tagName, timestamp, temperature, pressure, quality);
                                System.out.println("Processed " + tagName + " at " + timestamp + ": " +
                                                 processResult.fetchRow(0).getString("status"));
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================
    // Alert Management
    // ========================================

    public VoltTable getAlertsByNode(String nodeId, boolean activeOnly) throws Exception {
        return client.callProcedureAsync("GetAlertsByNode", nodeId, activeOnly)
            .thenApply(response -> checkResponse("GetAlertsByNode", response).getResults()[0])
            .get();
    }

    public void escalateAlert(String nodeId, long alertId, long timestamp) throws Exception {
        client.callProcedureAsync("EscalateAlert", nodeId, alertId, timestamp)
            .thenApply(response -> checkResponse("EscalateAlert", response))
            .get();
    }

    public void recoverAlert(String nodeId, String tagName, long timestamp) throws Exception {
        client.callProcedureAsync("RecoverAlert", nodeId, tagName, timestamp)
            .thenApply(response -> checkResponse("RecoverAlert", response))
            .get();
    }

    // ========================================
    // Rule 3: Rolling Window Analysis
    // ========================================

    public VoltTable analyzeRollingWindow(String nodeId, long windowStart, long windowEnd) throws Exception {
        return client.callProcedureAsync("AnalyzeRollingWindow", nodeId, windowStart, windowEnd)
            .thenApply(response -> checkResponse("AnalyzeRollingWindow", response).getResults()[0])
            .get();
    }

    // ========================================
    // Rule 6: Hierarchical Roll-Up
    // ========================================

    public VoltTable computeHierarchicalMetrics() throws Exception {
        return client.callProcedureAsync("ComputeHierarchicalMetrics")
            .thenApply(response -> checkResponse("ComputeHierarchicalMetrics", response).getResults()[0])
            .get();
    }

    public VoltTable getHierarchicalMetrics(String nodeId) throws Exception {
        return client.callProcedureAsync("@AdHoc",
            "SELECT * FROM HIERARCHICAL_METRICS WHERE node_id = '" + nodeId + "';")
            .thenApply(response -> checkResponse("GetHierarchicalMetrics", response).getResults()[0])
            .get();
    }

    // ========================================
    // Rule 7: Top Contributors
    // ========================================

    public VoltTable getTopContributors(String parentNodeId, int topN, boolean byActiveAlerts) throws Exception {
        return client.callProcedureAsync("GetTopContributors", parentNodeId, topN, byActiveAlerts)
            .thenApply(response -> checkResponse("GetTopContributors", response).getResults()[0])
            .get();
    }

    // ========================================
    // Cleanup
    // ========================================

    public void deleteAllData() throws Exception {
        client.callProcedureAsync("@AdHoc", "DELETE FROM HIERARCHICAL_METRICS;")
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM ROLLING_WINDOW_AGG;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM TAG_ASSET_MAPPING;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM SENSOR_READING;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM ALERT;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM ASSET;"))
            .get();
        System.out.println("All data deleted.");
    }

    // ========================================
    // Internal
    // ========================================

    private static ClientResponse checkResponse(String procName, ClientResponse response) {
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException(procName + " failed: " + response.getStatusString());
        }
        return response;
    }

    // ========================================
    // Utility
    // ========================================

    public static void printTable(String label, VoltTable table) {
        System.out.println("\n--- " + label + " ---");
        System.out.println(table.toFormattedString());
    }

    // ========================================
    // Main entry point
    // ========================================

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21211;

        Client2Config config = new Client2Config();
        Client2 client = ClientFactory.createClient(config);
        client.connectSync(host, port);
        System.out.println("Connected to VoltDB at " + host + ":" + port);

        try {
            // One-time schema setup
            new VoltDBSetup(client).initSchemaIfNeeded();

            AssetMonitoringApp app = new AssetMonitoringApp(client);

            // Clean slate
            app.deleteAllData();

            // Setup asset hierarchy
            System.out.println("\n=== Setting up Asset Hierarchy ===");
            app.upsertAsset("fleet-001", "Fleet Alpha", "Fleet", null, "{\"description\":\"Primary fleet\"}");
            app.upsertAsset("asset-001", "Asset1", "Machine", "fleet-001", "{\"tempThreshold\":50,\"pressureThreshold\":30}");
            app.upsertAsset("asset-002", "Asset2", "Machine", "fleet-001", "{\"tempThreshold\":50,\"pressureThreshold\":30}");

            // Register tag mappings
            app.registerTagMapping("Asset1.Compressor_Sensor_01", "asset-001");
            app.registerTagMapping("Asset2.Compressor_Sensor_01", "asset-002");

            printTable("Asset Hierarchy", app.getAssetHierarchy());

            // Simulate sensor data processing
            System.out.println("\n=== Processing Sensor Readings ===");

            long baseTime = System.currentTimeMillis();

            // Rule 1 & 2: Temperature and Sequential Rule
            System.out.println("\nRule 1 Test: Temperature breach (temp=55, pressure=20)");
            VoltTable result1 = app.processSensorReading("Asset1.Compressor_Sensor_01", baseTime, 55.0, 20.0, 1);
            printTable("Result", result1);

            System.out.println("\nRule 2 Test: Sequential rule breach (temp=55, pressure=35)");
            VoltTable result2 = app.processSensorReading("Asset1.Compressor_Sensor_01", baseTime + 60000, 55.0, 35.0, 1);
            printTable("Result", result2);

            // Rule 4: Continuous condition (escalation to Critical)
            System.out.println("\nRule 4 Test: Continued breach - should escalate to Critical");
            VoltTable result3 = app.processSensorReading("Asset1.Compressor_Sensor_01", baseTime + 120000, 60.0, 40.0, 1);
            printTable("Result", result3);

            // Check alerts
            printTable("Active Alerts for asset-001", app.getAlertsByNode("asset-001", true));

            // Rule 5: Recovery
            System.out.println("\nRule 5 Test: Recovery (temp=45)");
            VoltTable result4 = app.processSensorReading("Asset1.Compressor_Sensor_01", baseTime + 180000, 45.0, 20.0, 1);
            printTable("Result", result4);

            printTable("Alerts after recovery", app.getAlertsByNode("asset-001", false));

            // Rule 3: Rolling Window (simulate 10 minutes of data)
            System.out.println("\n=== Rule 3: Rolling Window Analysis ===");
            long windowStart = baseTime;
            long windowEnd = baseTime + (10 * 60 * 1000); // 10 minutes

            // Generate multiple readings in the window
            for (int i = 0; i < 20; i++) {
                long ts = windowStart + (i * 30000); // Every 30 seconds
                double temp = 45.0 + (i % 2 == 0 ? 10.0 : 0.0); // Alternating high/low
                double pressure = 25.0 + (i % 2 == 0 ? 10.0 : 0.0);
                app.processSensorReading("Asset2.Compressor_Sensor_01", ts, temp, pressure, 1);
            }

            VoltTable windowResult = app.analyzeRollingWindow("asset-002", windowStart, windowEnd);
            printTable("Rolling Window Analysis", windowResult);

            // Rule 6: Hierarchical Roll-Up
            System.out.println("\n=== Rule 6: Hierarchical Roll-Up ===");
            VoltTable rollupResult = app.computeHierarchicalMetrics();
            printTable("Metrics Computation Result", rollupResult);

            printTable("Fleet Metrics", app.getHierarchicalMetrics("fleet-001"));
            printTable("Asset1 Metrics", app.getHierarchicalMetrics("asset-001"));
            printTable("Asset2 Metrics", app.getHierarchicalMetrics("asset-002"));

            // Rule 7: Top Contributors
            System.out.println("\n=== Rule 7: Top Contributors ===");
            VoltTable topContributors = app.getTopContributors("fleet-001", 5, false);
            printTable("Top 5 Contributors by Total Alerts", topContributors);

            // Sample JSON input processing
            System.out.println("\n=== Processing JSON Input ===");
            String sampleJson = """
                {
                  "tags": [
                    {
                      "name": "Asset1.Compressor_Sensor_01",
                      "results": [
                        {
                          "values": [
                            [%d, 150.5, 1],
                            [%d, 151.2, 3]
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(baseTime + 300000, baseTime + 360000);

            app.processJsonInput(sampleJson);
            printTable("Alerts after JSON processing", app.getAlertsByNode("asset-001", false));

        } finally {
            client.close();
        }
    }
}
