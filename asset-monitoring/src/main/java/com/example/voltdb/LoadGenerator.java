package com.example.voltdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PoC Load Generator for Asset Monitoring System
 *
 * Workload Definition:
 * - Total sensors: 3,000
 * - Real-time streaming: 2,500 sensors @ 1 value/sec each
 * - Batched ingestion: 500 sensors @ 120 datapoints every 2 minutes
 * - Real-time event size: ~150 bytes
 * - Batched event size: ~3 KB
 * - Target throughput: ~2 MB/s
 */
public class LoadGenerator {

    private static final int TOTAL_SENSORS = 3000;
    private static final int REALTIME_SENSORS = 2500;
    private static final int BATCHED_SENSORS = 500;
    private static final int BATCH_SIZE = 120; // datapoints per batch
    private static final int BATCH_INTERVAL_MS = 120000; // 2 minutes

    private final Client2 client;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final Random random = new Random();
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong eventsGenerated = new AtomicLong(0);
    private final AtomicLong bytesGenerated = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private volatile boolean running = true;

    // Track sensors
    private final List<String> realtimeSensors = new ArrayList<>();
    private final List<String> batchedSensors = new ArrayList<>();
    private final Map<String, String> sensorToNode = new ConcurrentHashMap<>();

    public LoadGenerator(Client2 client) {
        this.client = client;
        this.executor = Executors.newFixedThreadPool(20); // For real-time generation
        this.scheduler = Executors.newScheduledThreadPool(2); // For batched generation
        initializeSensors();
    }

    private void initializeSensors() {
        // Generate sensor tags and register mappings
        // Distribute sensors across 30 machines (100 sensors per machine)
        int sensorsPerMachine = TOTAL_SENSORS / 30;

        for (int machineNum = 1; machineNum <= 30; machineNum++) {
            String machineId = String.format("machine-%03d", machineNum);

            for (int sensorNum = 1; sensorNum <= sensorsPerMachine; sensorNum++) {
                String tagName = String.format("Machine%03d.Sensor_%03d", machineNum, sensorNum);

                // First 500 sensors are batched, rest are real-time
                if ((machineNum - 1) * sensorsPerMachine + sensorNum <= BATCHED_SENSORS) {
                    batchedSensors.add(tagName);
                } else {
                    realtimeSensors.add(tagName);
                }

                sensorToNode.put(tagName, machineId);

                // Register tag mapping in VoltDB
                try {
                    client.callProcedureSync("RegisterTagMapping", tagName, machineId);
                } catch (Exception e) {
                    System.err.println("Failed to register tag " + tagName + ": " + e.getMessage());
                }
            }
        }

        System.out.printf("Initialized %d real-time sensors and %d batched sensors%n",
                         realtimeSensors.size(), batchedSensors.size());
    }

    public void start() {
        System.out.println("Starting PoC Load Generator...");
        System.out.printf("Real-time sensors: %d @ 1 event/sec each%n", REALTIME_SENSORS);
        System.out.printf("Batched sensors: %d @ %d datapoints every %d seconds%n",
                         BATCHED_SENSORS, BATCH_SIZE, BATCH_INTERVAL_MS / 1000);

        // Start real-time streaming (divide sensors among threads)
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

        // Start statistics reporter
        scheduler.scheduleAtFixedRate(
            this::reportStatistics,
            10000, // Initial delay 10 seconds
            10000, // Every 10 seconds
            TimeUnit.MILLISECONDS
        );

        System.out.println("Load generator started successfully!");
    }

    private void generateRealtimeData(int startIdx, int endIdx) {
        long lastReportTime = System.currentTimeMillis();

        while (running) {
            long startTime = System.currentTimeMillis();

            // Generate one event per sensor in this thread's range
            for (int i = startIdx; i < endIdx && i < realtimeSensors.size(); i++) {
                try {
                    String tagName = realtimeSensors.get(i);
                    String json = generateRealtimeEvent(tagName);

                    // Send to VoltDB via procedure call
                    sendToVoltDB(tagName, json);

                    eventsGenerated.incrementAndGet();
                    bytesGenerated.addAndGet(json.length());

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    if (errorCount.get() % 1000 == 0) {
                        System.err.println("Error generating real-time data: " + e.getMessage());
                    }
                }
            }

            // Sleep to maintain 1 event/sec rate
            long elapsed = System.currentTimeMillis() - startTime;
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
        System.out.println("Generating batched data for " + BATCHED_SENSORS + " sensors...");
        long startTime = System.currentTimeMillis();
        int totalDatapoints = 0;

        try {
            // Generate batched data for all batched sensors concurrently
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String tagName : batchedSensors) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String json = generateBatchedEvent(tagName, BATCH_SIZE);
                        sendToVoltDB(tagName, json);
                        eventsGenerated.incrementAndGet();
                        bytesGenerated.addAndGet(json.length());
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }, executor);

                futures.add(future);
            }

            // Wait for all batch submissions to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            totalDatapoints = BATCHED_SENSORS * BATCH_SIZE;
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.printf("Batched data generated: %d sensors, %d datapoints in %d ms%n",
                             BATCHED_SENSORS, totalDatapoints, elapsed);

        } catch (Exception e) {
            System.err.println("Error in batched data generation: " + e.getMessage());
        }
    }

    private String generateRealtimeEvent(String tagName) {
        long timestamp = System.currentTimeMillis();
        double temperature = 35.0 + random.nextDouble() * 30.0; // 35-65°C
        double pressure = 15.0 + random.nextDouble() * 30.0;    // 15-45 bar
        int quality = random.nextInt(10) < 9 ? 1 : 3; // 90% good quality

        // Generate JSON matching the input format (~150 bytes)
        return String.format(
            "{\"tags\":[{\"name\":\"%s\",\"results\":[{\"values\":[[%d,%.2f,%d,%.2f]]}]}]}",
            tagName, timestamp, temperature, quality, pressure
        );
    }

    private String generateBatchedEvent(String tagName, int batchSize) {
        StringBuilder json = new StringBuilder();
        json.append("{\"tags\":[{\"name\":\"").append(tagName).append("\",\"results\":[{\"values\":[");

        long baseTime = System.currentTimeMillis() - (batchSize * 1000); // Last N seconds

        for (int i = 0; i < batchSize; i++) {
            long timestamp = baseTime + (i * 1000);
            double temperature = 35.0 + random.nextDouble() * 30.0;
            double pressure = 15.0 + random.nextDouble() * 30.0;
            int quality = random.nextInt(10) < 9 ? 1 : 3;

            if (i > 0) json.append(",");
            json.append(String.format("[%d,%.2f,%d,%.2f]", timestamp, temperature, quality, pressure));
        }

        json.append("]}]}]}");
        return json.toString();
    }

    private void sendToVoltDB(String tagName, String jsonData) throws Exception {
        // Parse JSON and send individual readings to VoltDB
        Map<String, Object> data = jsonMapper.readValue(jsonData, Map.class);
        List<Map<String, Object>> tags = (List<Map<String, Object>>) data.get("tags");

        for (Map<String, Object> tag : tags) {
            String name = (String) tag.get("name");
            List<Map<String, Object>> results = (List<Map<String, Object>>) tag.get("results");

            for (Map<String, Object> result : results) {
                List<List<Object>> values = (List<List<Object>>) result.get("values");

                for (List<Object> value : values) {
                    long timestamp = ((Number) value.get(0)).longValue();
                    double temperature = ((Number) value.get(1)).doubleValue();
                    int quality = ((Number) value.get(2)).intValue();
                    double pressure = value.size() > 3 ? ((Number) value.get(3)).doubleValue() : 0.0;

                    // Call ProcessSensorReading procedure
                    client.callProcedureAsync("ProcessSensorReading",
                                             name, timestamp, temperature, pressure, quality);
                }
            }
        }
    }

    private void reportStatistics() {
        long events = eventsGenerated.get();
        long bytes = bytesGenerated.get();
        long errors = errorCount.get();

        double mbPerSec = (bytes / 1024.0 / 1024.0) / 10.0; // Over last 10 seconds
        double eventsPerSec = events / 10.0;

        System.out.printf("Stats: %.2f events/sec, %.2f MB/sec, %d total events, %d errors%n",
                         eventsPerSec, mbPerSec, events, errors);

        // Reset counters for next interval
        eventsGenerated.set(0);
        bytesGenerated.set(0);
    }

    public void stop() {
        System.out.println("Stopping load generator...");
        running = false;
        executor.shutdown();
        scheduler.shutdown();

        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Load generator stopped.");
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21211;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 600; // Default 10 minutes

        Client2Config config = new Client2Config();
        Client2 client = ClientFactory.createClient(config);
        client.connectSync(host, port);
        System.out.println("Connected to VoltDB at " + host + ":" + port);

        LoadGenerator generator = new LoadGenerator(client);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            generator.stop();
            try {
                client.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        // Start load generation
        generator.start();

        // Run for specified duration
        if (durationSec > 0) {
            System.out.printf("Running for %d seconds (Ctrl+C to stop earlier)...%n", durationSec);
            Thread.sleep(durationSec * 1000L);
            generator.stop();
            client.close();
        } else {
            // Run indefinitely
            System.out.println("Running indefinitely (Ctrl+C to stop)...");
            Thread.currentThread().join();
        }
    }
}
