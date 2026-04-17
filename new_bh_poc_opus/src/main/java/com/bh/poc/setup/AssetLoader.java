package com.bh.poc.setup;

import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;

/**
 * Seeds VoltDB with:
 *   - a shallow asset hierarchy (Fleet/Site/Group/Machine) suitable for a PoC run,
 *   - SENSOR_TAG rows mapping the simulator's tag names to nodes and thresholds.
 *
 * Run once after deploying DDL:
 *   java -cp <jar> com.bh.poc.setup.AssetLoader localhost:21212 2500 500
 */
public final class AssetLoader {
    public static void main(String[] args) throws Exception {
        String servers    = args.length > 0 ? args[0] : "localhost:21212";
        int    streamCnt  = args.length > 1 ? Integer.parseInt(args[1]) : 2500;
        int    batchedCnt = args.length > 2 ? Integer.parseInt(args[2]) : 500;

        Client c = ClientFactory.createClient(new ClientConfig());
        for (String s : servers.split(",")) c.createConnection(s.trim());
        System.out.println("[AssetLoader] connected to " + servers);

        // Top of the hierarchy
        c.callProcedure("UpsertAsset", "FLEET-1", "Fleet 1", "Fleet", null, 0, "{}");
        c.callProcedure("UpsertAsset", "SITE-1",  "Site 1",  "Site",  "FLEET-1", 1, "{}");
        c.callProcedure("UpsertAsset", "GROUP-1", "Group 1", "Group", "SITE-1",  2, "{}");

        int totalSensors = streamCnt + batchedCnt;
        int totalAssets  = totalSensors / 2;

        for (int a = 0; a < totalAssets; a++) {
            String assetId = (a < streamCnt/2 ? "StreamAsset" : "BatchedAsset") + a;
            c.callProcedure("UpsertAsset", assetId, assetId, "Machine", "GROUP-1", 3, "{}");
        }

        // Two tags per asset: Temperature + Pressure
        for (int a = 0; a < totalAssets; a++) {
            boolean batched = a >= streamCnt/2;
            String prefix = batched ? "BatchedAsset" : "StreamAsset";
            String assetId = prefix + a;
            String tTag = prefix + a + ".Sensor_Temperature";
            String pTag = prefix + a + ".Sensor_Pressure";
            c.callProcedure("UpsertSensorTag", tTag, assetId, "TEMPERATURE", 50.0, 30.0, 60.0);
            c.callProcedure("UpsertSensorTag", pTag, assetId, "PRESSURE",    50.0, 30.0, 60.0);
        }
        c.drain(); c.close();
        System.out.println("[AssetLoader] loaded hierarchy + " + (totalAssets*2) + " sensor tags");
    }
}
