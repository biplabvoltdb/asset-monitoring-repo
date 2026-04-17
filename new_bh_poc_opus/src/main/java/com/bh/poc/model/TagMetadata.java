package com.bh.poc.model;

/** Metadata + thresholds for a sensor tag (from VoltDB SENSOR_TAG). */
public class TagMetadata {
    public final String tagName;
    public final String nodeId;
    public final String metric;            // TEMPERATURE | PRESSURE
    public final double tempThreshold;     // Rule 1 / Rule 3 / Rule 4 / Rule 5
    public final double pressureThreshold; // Rule 2 / Rule 3
    public final double tempHigh;          // Rule 2 escalation threshold

    public TagMetadata(String tagName, String nodeId, String metric,
                       double tempThreshold, double pressureThreshold, double tempHigh) {
        this.tagName = tagName; this.nodeId = nodeId; this.metric = metric;
        this.tempThreshold = tempThreshold;
        this.pressureThreshold = pressureThreshold;
        this.tempHigh = tempHigh;
    }
}
