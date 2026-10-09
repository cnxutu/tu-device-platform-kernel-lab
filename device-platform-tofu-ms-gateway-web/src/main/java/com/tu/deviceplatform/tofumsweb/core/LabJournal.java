package com.tu.deviceplatform.tofumsweb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Bounded, process-local observations; no device or platform persistence. */
public final class LabJournal {
    private static final int CAPACITY = 1000;
    private final ObjectMapper mapper;
    private final Deque<Map<String, Object>> records = new ArrayDeque<>();
    private long sequence;

    public LabJournal(ObjectMapper mapper) { this.mapper = mapper; }

    public synchronized void add(String type, Object data) {
        add(type, data, null);
    }

    public synchronized void add(String type, Object data, Object context) {
        // Freeze data before asynchronous callbacks or callers can change it.
        JsonNode copy = mapper.valueToTree(data);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sequence", ++sequence); record.put("time", System.currentTimeMillis());
        record.put("type", type); record.put("data", copy);
        if (context != null) { record.put("context", mapper.valueToTree(context)); }
        if (records.size() == CAPACITY) { records.removeFirst(); }
        records.addLast(record);
    }

    public synchronized Map<String, Object> since(long cursor) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> item : records) {
            if (((Long) item.get("sequence")) > cursor) { events.add(item); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cursor", sequence); result.put("events", events);
        result.put("truncated", !records.isEmpty() && cursor < (Long) records.getFirst().get("sequence") - 1);
        return result;
    }
}
