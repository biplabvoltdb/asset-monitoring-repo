package com.example.voltsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.voltdb.stream.api.ExecutionContext.ConfigurationContext;
import org.voltdb.stream.api.pipeline.VoltPipeline;
import org.voltdb.stream.api.pipeline.VoltStreamBuilder;
import org.voltdb.stream.plugin.kafka.api.KafkaSourceConfigBuilder;
import org.voltdb.stream.plugin.volt.api.VoltProcedureRequest;
import org.voltdb.stream.plugin.volt.api.VoltSinks;

/**
 * VoltSP Pipeline for Asset Monitoring System
 *
 * Architecture: Kafka → VoltSP → VoltDB
 *
 * Flow:
 * 1. Consume sensor data from Kafka topic (sensor-data)
 * 2. Parse JSON to extract sensor readings
 * 3. Call VoltDB ProcessSensorReading procedure via VoltDB sink
 */
public class SensorMonitoringPipeline implements VoltPipeline {

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public void define(VoltStreamBuilder stream) {
        ConfigurationContext config = stream.getExecutionContext().configurator();

        // Read configuration with defaults
        String kafkaBootstrap = config.findByPath("kafka.bootstrap.servers").orElse("localhost:9092");
        String inputTopic = config.findByPath("kafka.input.topic").orElse("sensor-data");
        String consumerGroup = config.findByPath("kafka.consumer.group").orElse("asset-monitoring-group");

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  VoltSP Asset Monitoring Pipeline Starting");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("Kafka: " + kafkaBootstrap + " → Topic: " + inputTopic);
        System.out.println("Consumer Group: " + consumerGroup);
        System.out.println("VoltDB Resource: primary-cluster (from config)");
        System.out.println("═══════════════════════════════════════════════════════════");

        // Main pipeline: Kafka → Parse → VoltDB Procedure
        stream
            .withName("asset-monitoring-pipeline")
            .consumeFromSource(KafkaSourceConfigBuilder.<String>builder()
                .withTopicNames(inputTopic)
                .withBootstrapServers(kafkaBootstrap)
                .withGroupId(consumerGroup)
            )
            .processWith(kafkaRecord -> {
                try {
                    // Extract the string value from Kafka record
                    String json = kafkaRecord.getValue();

                    // Parse JSON structure
                    // Format: {"tags":[{"name":"Machine001.Sensor_001","results":[{"values":[[timestamp,temp,quality,pressure]]}]}]}
                    JsonNode root = jsonMapper.readTree(json);
                    JsonNode tags = root.get("tags");

                    if (tags != null && tags.isArray() && tags.size() > 0) {
                        JsonNode tag = tags.get(0);
                        String tagName = tag.get("name").asText();
                        JsonNode results = tag.get("results");

                        if (results != null && results.isArray() && results.size() > 0) {
                            JsonNode result = results.get(0);
                            JsonNode values = result.get("values");

                            if (values != null && values.isArray() && values.size() > 0) {
                                JsonNode value = values.get(0);

                                long timestamp = value.get(0).asLong();
                                double temperature = value.get(1).asDouble();
                                int quality = value.get(2).asInt();
                                double pressure = value.size() > 3 ? value.get(3).asDouble() : 0.0;

                                // Create VoltProcedureRequest to call ProcessSensorReading
                                return new VoltProcedureRequest(
                                    "ProcessSensorReading",
                                    new Object[]{tagName, timestamp, temperature, pressure, quality}
                                );
                            }
                        }
                    }

                } catch (Exception e) {
                    System.err.println("Error parsing sensor data: " + e.getMessage());
                }

                return null; // Skip if parsing fails
            })
            .filter(voltRequest -> voltRequest != null) // Only process valid requests
            .terminateWithSink(VoltSinks.procedureCall()
                .withVoltClientResource("primary-cluster")
            );
    }
}
