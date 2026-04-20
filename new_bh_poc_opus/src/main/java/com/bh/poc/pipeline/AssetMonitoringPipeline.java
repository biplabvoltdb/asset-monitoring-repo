package com.bh.poc.pipeline;

import com.bh.poc.model.Alert;
import com.bh.poc.model.SensorEvent;
import com.bh.poc.processors.AlertEvaluationProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.voltdb.stream.api.ExecutionContext.ConfigurationContext;
import org.voltdb.stream.api.kafka.KafkaRequest;
import org.voltdb.stream.api.pipeline.VoltPipeline;
import org.voltdb.stream.api.pipeline.VoltStreamBuilder;
import org.voltdb.stream.api.pipeline.VoltStreamFunction;
import org.voltdb.stream.plugin.kafka.api.KafkaSourceConfigBuilder;
import org.voltdb.stream.plugin.kafka.api.KafkaSinkConfigBuilder;
import org.voltdb.stream.plugin.kafka.api.KafkaStartingOffset;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * VoltSP pipeline:
 *
 *   Kafka(sensor-events) --[flat map]--> SensorEvent
 *                       --[Java processor: rule engine + VoltDB writes via voltdb-client resource]
 *                       --> Alert
 *                       --[value mapper: Alert -> JSON]
 *                       --> Kafka(alert)
 *
 * All externalised values (Kafka bootstrap, topic names, VoltDB servers/user/password)
 * live in the runtime configuration file (see config/pipeline-config.yaml). The
 * voltdb-client resource named "voltdb" is declared there; the processor opens
 * a matching client using the same connection parameters.
 */
public final class AssetMonitoringPipeline implements VoltPipeline {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void define(VoltStreamBuilder stream) {
        ConfigurationContext cfg = stream.getExecutionContext().configurator();

        List<String> bootstrap  = splitCsv(cfg.findByPath("kafka.bootstrap").orElse("localhost:9092"));
        String sourceTopic      = cfg.findByPath("kafka.sourceTopic").orElse("sensor-events");
        String alertTopic       = cfg.findByPath("kafka.alertTopic").orElse("alert");
        String groupId          = cfg.findByPath("kafka.groupId").orElse("bh-asset-monitor");
        String voltServers      = cfg.findByPath("voltdb.servers").orElse("localhost:21212");
        String voltUser         = cfg.findByPath("voltdb.user").orElse("");
        String voltPassword     = cfg.findByPath("voltdb.password").orElse("");

        AlertEvaluationProcessor ruleEngine =
                new AlertEvaluationProcessor(voltServers, voltUser, voltPassword);

        stream
            .withName("bh-asset-monitoring")
            .consumeFromSource(
                KafkaSourceConfigBuilder.<String>builder()
                    .withGroupId(groupId)
                    .withBootstrapServers(bootstrap.toArray(new String[0]))
                    .withTopicNames(sourceTopic)
                    .withStartingOffset(KafkaStartingOffset.LATEST)
                    .withPollTimeout(Duration.ofMillis(250))
                    .withKeyDeserializer(StringDeserializer.class)
                    .withValueDeserializer(StringDeserializer.class)
            )
            // 1. KafkaRequest -> JSON payload -> flat stream of SensorEvent via a VoltStreamFunction
            .processWith((VoltStreamFunction<KafkaRequest<?, String>, SensorEvent>) (req, out, ctx) -> {
                for (SensorEvent ev : parsePayload(req.getValue())) out.consume(ev);
            })
            // 2. Rule engine: Java processor that talks to VoltDB via the voltdb-client resource.
            //    Uses VoltStreamFunction so we only emit non-null alerts downstream.
            .processWith((VoltStreamFunction<SensorEvent, Alert>) (ev, out, ctx) -> {
                Alert a = ruleEngine.process(ev);
                if (a != null) out.consume(a);
            })
            // 4. Serialise alert to JSON for Kafka sink
            .processWith(AssetMonitoringPipeline::alertToJson)
            // 5. Kafka sink - topic "alert"
            .terminateWithSink(
                KafkaSinkConfigBuilder.<String>builder()
                    .withBootstrapServers(bootstrap.toArray(new String[0]))
                    .withTopicName(alertTopic)
                    .withKeySerializer(StringSerializer.class)
                    .withValueSerializer(StringSerializer.class)
            );
    }

    // ----- helpers -----

    /** Parse the incoming Kafka JSON payload (see PoC sample) into a list of SensorEvent. */
    static List<SensorEvent> parsePayload(String raw) {
        List<SensorEvent> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode tags = root.path("tags");
            for (JsonNode tag : tags) {
                String tagName = tag.path("name").asText();
                for (JsonNode res : tag.path("results")) {
                    for (JsonNode row : res.path("values")) {
                        long ts = row.get(0).asLong();
                        double value = row.get(1).asDouble();
                        int q = row.size() > 2 ? row.get(2).asInt() : 1;
                        out.add(new SensorEvent(tagName, ts, value, q));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[parsePayload] bad JSON: " + e.getMessage());
        }
        return out;
    }

    static String alertToJson(Alert a) {
        if (a == null) return null;
        try { return JSON.writeValueAsString(a); }
        catch (Exception e) { return "{\"error\":\"serialise\"}"; }
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) { String t = x.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
}
