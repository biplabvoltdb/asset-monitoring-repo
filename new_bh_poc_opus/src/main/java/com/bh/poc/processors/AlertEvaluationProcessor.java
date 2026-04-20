package com.bh.poc.processors;

import com.bh.poc.model.Alert;
import com.bh.poc.model.SensorEvent;
import com.bh.poc.model.TagMetadata;
import com.bh.poc.pipeline.VoltClientHolder;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single Java processor that:
 *   1. takes a decoded {@link SensorEvent} from Kafka,
 *   2. queries VoltDB (via the voltdb-client resource) for tag metadata / thresholds,
 *   3. evaluates rules R1..R5,
 *   4. writes resulting alerts to VoltDB ALERT table,
 *   5. returns the Alert object downstream (or null to drop); VoltSP will sink
 *      non-null alerts to the "alert" Kafka topic.
 *
 * All VoltDB interactions happen through a process-wide VoltDB client configured
 * from the same endpoint as the "voltdb" voltdb-client resource (see pipeline
 * runtime config).
 */
public final class AlertEvaluationProcessor {

    // Small per-tag metadata cache to avoid calling GetSensorTag for every sample.
    private static final ConcurrentHashMap<String, TagMetadata> META_CACHE = new ConcurrentHashMap<>();

    private final String voltServers;
    private final String voltUser;
    private final String voltPassword;

    public AlertEvaluationProcessor(String voltServers, String voltUser, String voltPassword) {
        this.voltServers = voltServers;
        this.voltUser = voltUser;
        this.voltPassword = voltPassword;
    }

    /** Called once per Kafka record after deserialisation. */
    public Alert process(SensorEvent ev) {
        if (ev == null || ev.tagName == null) return null;
        try {
            Client c = VoltClientHolder.get(voltServers, voltUser, voltPassword);

            // 1. Metadata / threshold check
            TagMetadata md = META_CACHE.computeIfAbsent(ev.tagName, t -> lookupTag(c, t));
            if (md == null) return null; // unknown tag -> drop

            // 2. Rule evaluation pipeline - first matching rule wins
            Alert alert = evaluateRules(c, md, ev);
            if (alert == null) return null;

            // 3. Persist alert to VoltDB (UpsertAlert is TAG_NAME-partitioned)
            c.callProcedure("UpsertAlert",
                    alert.tagName, alert.alertId, alert.nodeId, alert.title,
                    alert.severity, alert.status, alert.ruleId, alert.ts, alert.value);

            // 4. Update rollup counters (NODE_ID-partitioned, separate call to avoid cross-partition violation)
            try {
                c.callProcedure("UpdateRollup", alert.nodeId, alert.status, alert.ts);
            } catch (Exception rollupEx) {
                // best-effort; don't fail the alert for a rollup counter miss
            }

            // 5. Return alert -> framework sinks it to Kafka "alert" topic
            return alert;
        } catch (Exception e) {
            // In a real pipeline we would route to a DLQ named sink.
            System.err.println("[AlertEvaluationProcessor] " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Rule implementations
    // -----------------------------------------------------------------------

    private Alert evaluateRules(Client c, TagMetadata md, SensorEvent ev) throws Exception {
        boolean isTemp = "TEMPERATURE".equalsIgnoreCase(md.metric);
        boolean isPressure = "PRESSURE".equalsIgnoreCase(md.metric);

        // Feed the rolling-window table for Rule 3 (Rule 3 needs pairs of temp & pressure
        // for the same node; in the PoC we forward whichever metric arrived, null for the other).
        if (isTemp) {
            c.callProcedure("AppendWindowSample", ev.tagName, ev.ts, ev.value, 0.0);
        } else if (isPressure) {
            c.callProcedure("AppendWindowSample", ev.tagName, ev.ts, 0.0, ev.value);
        }

        // Rule 5 - Recovery: temperature sensor dropping below threshold closes Active alerts
        if (isTemp && ev.value < md.tempThreshold) {
            VoltTable[] active = c.callProcedure("GetActiveAlertByTag", ev.tagName).getResults();
            if (active[0].getRowCount() > 0) {
                c.callProcedure("CloseAlert", ev.tagName, ev.ts);
                return Alert.of(UUID.randomUUID().toString(), ev.tagName, md.nodeId,
                        "Recovery: " + ev.tagName + " back within threshold",
                        "Low", "Closed", "R5", ev.ts, ev.value);
            }
            return null;
        }

        // Rule 4 - Continued breach escalation for temperature
        if (isTemp && ev.value > md.tempThreshold) {
            VoltTable[] active = c.callProcedure("GetActiveAlertByTag", ev.tagName).getResults();
            if (active[0].advanceRow()) {
                String existingId = active[0].getString(0);
                String severity   = active[0].getString(1);
                if (!"Critical".equals(severity)) {
                    c.callProcedure("EscalateAlert", ev.tagName, existingId, ev.ts, ev.value);
                    return Alert.of(existingId, ev.tagName, md.nodeId,
                            "Continued breach escalated to Critical",
                            "Critical", "Active", "R4", ev.ts, ev.value);
                }
                return null; // already Critical - suppress duplicate
            }
            // Rule 1 - First temperature breach
            return Alert.of(UUID.randomUUID().toString(), ev.tagName, md.nodeId,
                    "Temperature > " + md.tempThreshold,
                    "High", "Active", "R1", ev.ts, ev.value);
        }

        // Rule 2 - Sequential: pressure high, then watch temperature
        if (isPressure && ev.value > md.pressureThreshold) {
            // 1. Resolve the peer TEMPERATURE tag for this node
            VoltTable[] peerTagRs = c.callProcedure("GetPeerTempTag", md.nodeId).getResults();
            if (peerTagRs[0].advanceRow()) {
                String peerTag = peerTagRs[0].getString(0);
                // 2. Get the most recent temp sample for that peer tag (single-partition on TAG_NAME)
                VoltTable[] win = c.callProcedure("GetLatestTemp", peerTag).getResults();
                if (win[0].advanceRow()) {
                    double t = win[0].getDouble(0);
                    if (t > md.tempHigh) {
                        return Alert.of(UUID.randomUUID().toString(), peerTag, md.nodeId,
                                "Sequential rule: pressure>" + md.pressureThreshold + " then temp>" + md.tempHigh,
                                "High", "Active", "R2", ev.ts, t);
                    }
                }
            }
            // fall through to Rule 3
        }

        // Rule 3 - Rolling 10-min window: fraction of samples where temp>th AND pressure>th
        VoltTable[] evl = c.callProcedure("EvaluateWindow",
                ev.tagName, ev.ts, md.tempThreshold, md.pressureThreshold).getResults();
        if (evl[0].advanceRow()) {
            long n = evl[0].getLong(0);
            long hits = evl[0].getLong(1);
            if (n >= 10 && (double) hits / n >= 0.3) { // at least 30% of window samples breach
                return Alert.of(UUID.randomUUID().toString(), ev.tagName, md.nodeId,
                        String.format("Rolling-window: %d/%d samples breached", hits, n),
                        "Medium", "Active", "R3", ev.ts, ev.value);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Metadata lookup
    // -----------------------------------------------------------------------

    private TagMetadata lookupTag(Client c, String tagName) {
        try {
            ClientResponse r = c.callProcedure("GetSensorTag", tagName);
            VoltTable t = r.getResults()[0];
            if (!t.advanceRow()) return null;
            return new TagMetadata(
                    t.getString(0),   // TAG_NAME
                    t.getString(1),   // NODE_ID
                    t.getString(2),   // METRIC
                    t.getDouble(3),   // TEMP_THRESHOLD
                    t.getDouble(4),   // PRESSURE_THRESHOLD
                    t.getDouble(5));  // TEMP_HIGH
        } catch (Exception e) {
            return null;
        }
    }
}
