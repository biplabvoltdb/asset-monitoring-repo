package com.bh.poc.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;
import java.util.concurrent.*;

/**
 * Sensor workload simulator for the PoC:
 *   - 2500 real-time streaming sensors @ 1 sample / sec     -> ~2500 eps
 *   - 500  batched sensors @ 120 samples every 2 min        -> 60k samples / 2 min
 *
 * Temperature and pressure tags are interleaved; roughly 5% breach threshold to
 * exercise rules R1, R2, R3, R4, R5.
 */
public final class SensorSimulator {

    public static void main(String[] args) throws Exception {
        String brokers  = env("KAFKA_BOOTSTRAP", "localhost:9092");
        String topic    = env("SOURCE_TOPIC",    "sensor-events");
        int   streamCnt = Integer.parseInt(env("STREAM_SENSORS",  "2500"));
        int   batchCnt  = Integer.parseInt(env("BATCHED_SENSORS", "500"));

        Properties p = new Properties();
        p.put("bootstrap.servers", brokers);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("linger.ms", "20");
        p.put("batch.size", "65536");
        p.put("acks", "1");

        KafkaProducer<String,String> producer = new KafkaProducer<>(p);
        ObjectMapper json = new ObjectMapper();
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);

        // --- streaming load ---
        Runnable stream = () -> {
            for (int i = 0; i < streamCnt; i++) {
                String tag = tagFor(i, false);
                double v = nextValue(i);
                send(producer, topic, json, tag, System.currentTimeMillis(), v);
            }
        };
        sched.scheduleAtFixedRate(stream, 0, 1, TimeUnit.SECONDS);

        // --- batched load ---
        Runnable batch = () -> {
            for (int s = 0; s < batchCnt; s++) {
                String tag = tagFor(s, true);
                long now = System.currentTimeMillis();
                ObjectNode root = json.createObjectNode();
                ArrayNode tags = root.putArray("tags");
                ObjectNode tnode = tags.addObject();
                tnode.put("name", tag);
                ArrayNode results = tnode.putArray("results");
                ObjectNode r = results.addObject();
                ArrayNode vals = r.putArray("values");
                for (int k = 0; k < 120; k++) {
                    ArrayNode row = vals.addArray();
                    row.add(now - (120L - k) * 1000L);
                    row.add(nextValue(s));
                    row.add(1);
                }
                try {
                    producer.send(new ProducerRecord<>(topic, tag, json.writeValueAsString(root)));
                } catch (Exception e) { e.printStackTrace(); }
            }
        };
        sched.scheduleAtFixedRate(batch, 5, 120, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sched.shutdown(); producer.close();
        }));
        Thread.currentThread().join();
    }

    private static void send(KafkaProducer<String,String> prod, String topic, ObjectMapper json,
                             String tag, long ts, double v) {
        try {
            ObjectNode root = json.createObjectNode();
            ArrayNode tags = root.putArray("tags");
            ObjectNode tnode = tags.addObject(); tnode.put("name", tag);
            ArrayNode results = tnode.putArray("results");
            ObjectNode r = results.addObject();
            ArrayNode vals = r.putArray("values");
            ArrayNode row = vals.addArray();
            row.add(ts); row.add(v); row.add(1);
            prod.send(new ProducerRecord<>(topic, tag, json.writeValueAsString(root)));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static String tagFor(int i, boolean batched) {
        // Half temperature, half pressure; deterministic naming
        String metric = (i % 2 == 0) ? "Temperature" : "Pressure";
        String prefix = batched ? "Batched" : "Stream";
        int asset = i / 2;
        return prefix + "Asset" + asset + ".Sensor_" + metric;
    }

    private static double nextValue(int seed) {
        double r = Math.random();
        // 5% burst breaches - push above threshold to exercise rules
        if (r < 0.05) return 55 + Math.random() * 20;   // 55-75 (breach temp 50 or pressure 30)
        return 10 + Math.random() * 35;                  // 10-45 (normal)
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
