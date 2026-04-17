package com.bh.poc.model;

import java.io.Serializable;

/** One decoded sample extracted from the Kafka time-series payload. */
public class SensorEvent implements Serializable {
    public String tagName;     // e.g. "Asset1.Compressor_Sensor_01"
    public long   ts;          // epoch millis
    public double value;       // reading (temperature or pressure, depending on METRIC)
    public int    quality;     // optional quality flag (1/3/...)

    public SensorEvent() {}
    public SensorEvent(String tagName, long ts, double value, int quality) {
        this.tagName = tagName; this.ts = ts; this.value = value; this.quality = quality;
    }
    @Override public String toString() {
        return "SensorEvent{" + tagName + "@" + ts + "=" + value + ",q=" + quality + "}";
    }
}
