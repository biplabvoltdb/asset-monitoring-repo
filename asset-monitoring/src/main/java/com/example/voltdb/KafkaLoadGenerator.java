package com.example.voltdb;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PoC Load Generator for Asset Monitoring System
 * Produces sensor data to Kafka topic for VoltSP pipeline consumption
 *
 * Workload Definition:
 * - Total sensors: 3,000
 * - Real-time streaming: 2,500 sensors @ 1 value/sec each
 * - Batched ingestion: 500 sensors @ 120 datapoints every 2 minutes
 * - Real-time event size: ~150 bytes
 * - Batched event size: ~3 KB
 * - Target throughput: ~2 MB/s
 */
public class KafkaLoadGenerator {

    private static final int TOTAL_SENSORS = 3000;
    private static final int REALTIME_SENSORS = 2500;
    private static final int BATCHED_SENSORS = 500;
    private static final int BATCH_SIZE = 120;
    private static final int BATCH_INTERVAL_MS = 120000; // 2 minutes

    private final KafkaProducer<String, String> producer;
    private final String topicName;
    private final Random random = new Random();
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong eventsGenerated = new AtomicLong(0);
    private final AtomicLong bytesGenerated = new AtomicLong(0);
    private final AtomicLong kafkaErrors = new AtomicLong(0);

    private volatile boolean running = true;

    private final List<String> realtimeSensors = new ArrayList<>();
    private final List<String> batchedSensors = new ArrayList<>();

    public KafkaLoadGenerator(String bootstrapServers, String topicName) {
        this.topicName = topicName;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "32768");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "67108864"); // 64MB

        this.producer = new KafkaProducer<>(props);
        this.executor = Executors.newFixedThreadPool(20);
        this.scheduler = Executors.newScheduledThreadPool(2);

        initializeSensors();
    }

    private void initializeSensors() {
        // Generate 3000 sensor tags distributed across 30 machines
        int sensorsPerMachine = TOTAL_SENSORS / 30;

        for (int machineNum = 1; machineNum <= 30; machineNum++) {
            for (int sensorNum = 1; sensorNum <= sensorsPerMachine; sensorNum++) {
                String tagName = String.format("Machine%03d.Sensor_%03d", machineNum, sensorNum);

                // First 500 are batched, rest are real-time
                if ((machineNum - 1) * sensorsPerMachine + sensorNum <= BATCHED_SENSORS) {
                    batchedSensors.add(tagName);
                } else {
                    realtimeSensors.add(tagName);
                }
            }
        }

        System.out.printf("Initialized %d real-time sensors and %d batched sensors%n",
                         realtimeSensors.size(), batchedSensors.size());
    }

    public void start() {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        PoC Load Generator - Kafka Producer Mode           ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.printf("Target topic: %s%n", topicName);
        System.out.printf("Real-time sensors: %d @ 1 event/sec each = ~2500 events/sec%n", REALTIME_SENSORS);
        System.out.printf("Batched sensors: %d @ %d datapoints every %d seconds%n",
                         BATCHED_SENSORS, BATCH_SIZE, BATCH_INTERVAL_MS / 1000);
        System.out.printf("Expected throughput: ~2 MB/sec%n");
        System.out.println("─────────────────────────────────────────────────────────────");

        // Start real-time streaming (divide sensors among 20 threads)
        int sensorsPerThread = REALTIME_SENSORS / 20;
        for (int i = 0; i < 20; i++) {
            int startIdx = i * sensorsPerThread;
            int endIdx = (i == 19) ? REALTIME_SENSORS : startIdx + sensorsPerThread;
            executor.submit(() -> generateRealtimeData(startIdx, endIdx));
        }

        // Start batched ingestion (every 2 minutes)
        scheduler.scheduleAtFixedRate(
            this::generateBatchedData,
            5000, // Initial delay 5 seconds
            BATCH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        // Statistics reporter
        scheduler.scheduleAtFixedRate(
            this::reportStatistics,
            10000, // Report every 10 seconds
            10000,
            TimeUnit.MILLISECONDS
        );

        System.out.println("✓ Load generator started successfully!");
        System.out.println();
    }

    private void generateRealtimeData(int startIdx, int endIdx) {
        while (running) {
            long cycleStart = System.currentTimeMillis();

            // Generate one event per sensor (1 Hz rate)
            for (int i = startIdx; i < endIdx && i < realtimeSensors.size(); i++) {
                try {
                    String tagName = realtimeSensors.get(i);
                    String json = generateRealtimeEvent(tagName);

                    // Send to Kafka
                    producer.send(new ProducerRecord<>(topicName, tagName, json), (metadata, exception) -> {
                        if (exception != null) {
                            kafkaErrors.incrementAndGet();
                        }
                    });

                    eventsGenerated.incrementAndGet();
                    bytesGenerated.addAndGet(json.length());

                } catch (Exception e) {
                    kafkaErrors.incrementAndGet();
                }
            }

            // Sleep to maintain 1 Hz rate
            long elapsed = System.currentTimeMillis() - cycleStart;
            long sleepTime = 1000 - elapsed;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void generateBatchedData() {
        System.out.printf("[%s] Generating batched data for %d sensors (120 events each)...%n",
                         getCurrentTime(), BATCHED_SENSORS);
        long startTime = System.currentTimeMillis();
        AtomicInteger sent = new AtomicInteger(0);

        // Send 120 individual events per sensor concurrently (500 × 120 = 60,000 events)
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String tagName : batchedSensors) {
            // Each sensor sends 120 individual historical datapoints
            for (int i = 0; i < BATCH_SIZE; i++) {
                final int dataPointIndex = i;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // Generate individual event for this historical datapoint
                        long historicalTime = System.currentTimeMillis() - ((BATCH_SIZE - dataPointIndex) * 1000);
                        double temperature = generateTemperature();
                        double pressure = generatePressure();
                        int quality = random.nextInt(10) < 9 ? 1 : 3;

                        String json = String.format(
                            "{\"tags\":[{\"name\":\"%s\",\"results\":[{\"values\":[[%d,%.2f,%d,%.2f]]}]}]}",
                            tagName, historicalTime, temperature, quality, pressure
                        );

                        producer.send(new ProducerRecord<>(topicName, tagName, json), (metadata, exception) -> {
                            if (exception == null) {
                                sent.incrementAndGet();
                                eventsGenerated.incrementAndGet();
                                bytesGenerated.addAndGet(json.length());
                            } else {
                                kafkaErrors.incrementAndGet();
                            }
                        });

                    } catch (Exception e) {
                        kafkaErrors.incrementAndGet();
                    }
                }, executor);

                futures.add(future);
            }
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        producer.flush(); // Ensure all messages are sent

        long elapsed = System.currentTimeMillis() - startTime;
        int totalEvents = BATCHED_SENSORS * BATCH_SIZE;

        System.out.printf("[%s] ✓ Batch complete: %d sensors × %d events = %d total events sent in %d ms%n",
                         getCurrentTime(), BATCHED_SENSORS, BATCH_SIZE, sent.get(), elapsed);
    }

    private String generateRealtimeEvent(String tagName) {
        long timestamp = System.currentTimeMillis();
        double temperature = generateTemperature();
        double pressure = generatePressure();
        int quality = random.nextInt(10) < 9 ? 1 : 3; // 90% good quality

        // JSON format: ~150 bytes
        return String.format(
            "{\"tags\":[{\"name\":\"%s\",\"results\":[{\"values\":[[%d,%.2f,%d,%.2f]]}]}]}",
            tagName, timestamp, temperature, quality, pressure
        );
    }

    private String generateBatchedEvent(String tagName, int batchSize) {
        StringBuilder json = new StringBuilder();
        json.append("{\"tags\":[{\"name\":\"").append(tagName).append("\",\"results\":[{\"values\":[");

        long baseTime = System.currentTimeMillis() - (batchSize * 1000);

        for (int i = 0; i < batchSize; i++) {
            long timestamp = baseTime + (i * 1000);
            double temperature = generateTemperature();
            double pressure = generatePressure();
            int quality = random.nextInt(10) < 9 ? 1 : 3;

            if (i > 0) json.append(",");
            json.append(String.format("[%d,%.2f,%d,%.2f]", timestamp, temperature, quality, pressure));
        }

        json.append("]}]}]}");
        return json.toString(); // ~3 KB
    }

    private double generateTemperature() {
        // Normal: 35-50°C, 10% chance of breach (50-65°C)
        if (random.nextInt(10) == 0) {
            return 50.0 + random.nextDouble() * 15.0; // Breach zone
        }
        return 35.0 + random.nextDouble() * 15.0; // Normal zone
    }

    private double generatePressure() {
        // Normal: 15-30 bar, 10% chance of high pressure (30-45 bar)
        if (random.nextInt(10) == 0) {
            return 30.0 + random.nextDouble() * 15.0; // High pressure
        }
        return 15.0 + random.nextDouble() * 15.0; // Normal pressure
    }

    private void reportStatistics() {
        long events = eventsGenerated.getAndSet(0);
        long bytes = bytesGenerated.getAndSet(0);
        long errors = kafkaErrors.get();

        double eventsPerSec = events / 10.0;
        double mbPerSec = (bytes / 1024.0 / 1024.0) / 10.0;

        System.out.printf("[%s] ⚡ %.0f events/sec | %.2f MB/sec | %d total errors%n",
                         getCurrentTime(), eventsPerSec, mbPerSec, errors);
    }

    private String getCurrentTime() {
        return String.format("%tT", System.currentTimeMillis());
    }

    public void stop() {
        System.out.println("\nStopping load generator...");
        running = false;

        executor.shutdown();
        scheduler.shutdown();

        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        producer.close();
        System.out.println("✓ Load generator stopped");
    }

    public static void main(String[] args) throws Exception {
        String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        String topicName = args.length > 1 ? args[1] : "sensor-data";
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 0; // 0 = indefinite

        KafkaLoadGenerator generator = new KafkaLoadGenerator(bootstrapServers, topicName);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(generator::stop));

        // Start generation
        generator.start();

        // Run for duration or indefinitely
        if (durationSec > 0) {
            System.out.printf("Running for %d seconds (Ctrl+C to stop earlier)...%n%n", durationSec);
            Thread.sleep(durationSec * 1000L);
            generator.stop();
        } else {
            System.out.println("Running indefinitely (Ctrl+C to stop)...");
            System.out.println();
            Thread.currentThread().join();
        }
    }
}
