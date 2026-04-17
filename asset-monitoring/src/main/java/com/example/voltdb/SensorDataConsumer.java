package com.example.voltdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka Consumer for Sensor Data
 * Consumes from sensor-data topic and processes through VoltDB
 */
public class SensorDataConsumer {

    private final KafkaConsumer<String, String> consumer;
    private final Client2 voltClient;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final AtomicLong recordsProcessed = new AtomicLong(0);
    private final AtomicLong alertsCreated = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);

    private volatile boolean running = true;

    public SensorDataConsumer(String bootstrapServers, String groupId, String voltHost, int voltPort) throws Exception {
        // Kafka consumer setup
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000");

        this.consumer = new KafkaConsumer<>(props);

        // VoltDB client setup
        Client2Config voltConfig = new Client2Config();
        this.voltClient = ClientFactory.createClient(voltConfig);
        this.voltClient.connectSync(voltHost, voltPort);

        System.out.println("✓ Connected to VoltDB at " + voltHost + ":" + voltPort);
    }

    public void start(String topic) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║      Sensor Data Consumer - Kafka → VoltDB Pipeline       ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println("Consuming from topic: " + topic);
        System.out.println("Processing Rules: 1, 2, 4, 5 (real-time)");
        System.out.println("─────────────────────────────────────────────────────────────");

        consumer.subscribe(Collections.singletonList(topic));

        // Start statistics reporter
        Thread statsThread = new Thread(this::reportStatistics);
        statsThread.setDaemon(true);
        statsThread.start();

        System.out.println("✓ Consumer started - processing sensor data...\n");

        // Main consumption loop
        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record.value());
                }

            } catch (Exception e) {
                System.err.println("Error in consumer loop: " + e.getMessage());
                errors.incrementAndGet();
            }
        }
    }

    private void processRecord(String json) {
        try {
            JsonNode root = jsonMapper.readTree(json);
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
                                    double pressure = value.size() > 3 ? value.get(3).asDouble() : 0.0;

                                    // Call VoltDB procedure
                                    ClientResponse response = voltClient.callProcedureSync(
                                        "ProcessSensorReading",
                                        tagName, timestamp, temperature, pressure, quality
                                    );

                                    recordsProcessed.incrementAndGet();

                                    if (response.getStatus() == ClientResponse.SUCCESS) {
                                        var resultTable = response.getResults()[0];
                                        if (resultTable.advanceRow()) {
                                            String status = resultTable.getString("status");
                                            if ("ALERT_CREATED".equals(status) || "ESCALATED".equals(status)) {
                                                alertsCreated.incrementAndGet();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            errors.incrementAndGet();
            if (errors.get() % 100 == 0) {
                System.err.println("Processing error: " + e.getMessage());
            }
        }
    }

    private void reportStatistics() {
        long lastProcessed = 0;
        long lastAlerts = 0;

        while (running) {
            try {
                Thread.sleep(10000); // Every 10 seconds

                long currentProcessed = recordsProcessed.get();
                long currentAlerts = alertsCreated.get();
                long currentErrors = errors.get();

                long processedDelta = currentProcessed - lastProcessed;
                long alertsDelta = currentAlerts - lastAlerts;

                double recordsPerSec = processedDelta / 10.0;

                System.out.printf("[%tT] ⚡ %.0f records/sec | %d alerts (+%d) | %d total records | %d errors%n",
                                 System.currentTimeMillis(), recordsPerSec, currentAlerts, alertsDelta,
                                 currentProcessed, currentErrors);

                lastProcessed = currentProcessed;
                lastAlerts = currentAlerts;

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public void stop() {
        System.out.println("\nStopping consumer...");
        running = false;
        consumer.close();

        try {
            voltClient.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("✓ Consumer stopped");
    }

    public static void main(String[] args) throws Exception {
        String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        String groupId = args.length > 1 ? args[1] : "sensor-consumer-group";
        String topic = args.length > 2 ? args[2] : "sensor-data";
        String voltHost = args.length > 3 ? args[3] : "localhost";
        int voltPort = args.length > 4 ? Integer.parseInt(args[4]) : 21211;

        SensorDataConsumer consumer = new SensorDataConsumer(bootstrapServers, groupId, voltHost, voltPort);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(consumer::stop));

        // Start consuming
        consumer.start(topic);
    }
}
