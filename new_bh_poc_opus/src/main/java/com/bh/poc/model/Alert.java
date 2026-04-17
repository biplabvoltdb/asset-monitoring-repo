package com.bh.poc.model;

import java.io.Serializable;

/** Alert emitted by the rule engine; persisted to VoltDB ALERT table AND sinked to Kafka topic "alert". */
public class Alert implements Serializable {
    public String alertId;
    public String tagName;
    public String nodeId;
    public String title;
    public String severity;    // Low / Medium / High / Critical
    public String status;      // Active / Closed
    public String ruleId;      // R1..R5
    public long   ts;
    public double value;

    public Alert() {}

    public static Alert of(String alertId, String tagName, String nodeId, String title,
                           String severity, String status, String ruleId, long ts, double value) {
        Alert a = new Alert();
        a.alertId = alertId; a.tagName = tagName; a.nodeId = nodeId; a.title = title;
        a.severity = severity; a.status = status; a.ruleId = ruleId; a.ts = ts; a.value = value;
        return a;
    }
}
