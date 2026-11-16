package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ServiceRequestLoader {
    private static final int MAX_INPUT_CHARS = 262144;
    private static final int MAX_REQUESTS = 100;
    private static final Set<String> TOP_LEVEL_FIELDS = new HashSet<String>(Arrays.asList(
            "deviceId", "identifier", "params", "iotPlatformParams", "syncMode", "timeoutSeconds"));
    private static final Set<String> IDENTIFIERS = new HashSet<String>(Arrays.asList(
            "target_acquire", "target_follow"));

    private final ObjectMapper mapper;

    public ServiceRequestLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ObjectNode> parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Paste requestDTO JSON or IOT_DOWN log lines");
        }
        if (input.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("Input exceeds 256 KB");
        }

        String trimmed = input.trim();
        if (trimmed.startsWith("{") && trimmed.indexOf("requestDTO:") < 0) {
            return one(parseObject(trimmed));
        }

        List<ObjectNode> requests = new ArrayList<ObjectNode>();
        for (String line : input.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            int marker = value.indexOf("requestDTO:");
            if (marker >= 0) {
                requests.add(validate(parseObject(value.substring(marker + "requestDTO:".length()).trim())));
            } else if (value.startsWith("{")) {
                requests.add(validate(parseObject(value)));
            }
            if (requests.size() > MAX_REQUESTS) {
                throw new IllegalArgumentException("Import at most 100 service requests at a time");
            }
        }
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("No requestDTO JSON found");
        }
        return requests;
    }

    public ObjectNode validate(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Service request must be a JSON object");
        }
        ObjectNode request = (ObjectNode) value.deepCopy();
        request.fieldNames().forEachRemaining(name -> {
            if (!TOP_LEVEL_FIELDS.contains(name)) {
                throw new IllegalArgumentException("Unsupported top-level field: " + name);
            }
        });
        JsonNode deviceId = request.get("deviceId");
        if (deviceId == null || !deviceId.isIntegralNumber() || !deviceId.canConvertToLong()
                || deviceId.longValue() < 1) {
            throw new IllegalArgumentException("deviceId must be a positive integer");
        }
        String identifier = request.path("identifier").asText("");
        if (!IDENTIFIERS.contains(identifier)) {
            throw new IllegalArgumentException("identifier must be target_acquire or target_follow");
        }
        JsonNode params = request.get("params");
        if (params == null || !params.isObject() || !params.path("targetId").isTextual()
                || params.path("targetId").asText().trim().isEmpty()) {
            throw new IllegalArgumentException("params.targetId is required");
        }
        if (request.has("iotPlatformParams") && !request.path("iotPlatformParams").isObject()) {
            throw new IllegalArgumentException("iotPlatformParams must be an object");
        }
        JsonNode geo = params.path("position").path("geo");
        if (!geo.isObject()) {
            throw new IllegalArgumentException("params.position.geo is required");
        }
        BigDecimal longitude = decimal(geo.get("longitude"), "longitude");
        BigDecimal latitude = decimal(geo.get("latitude"), "latitude");
        decimal(geo.get("altitude"), "altitude");
        if (longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0
                || latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("GEO coordinates are out of range");
        }
        if (request.has("syncMode") && !Arrays.asList("BLOCK", "ASYNC").contains(request.path("syncMode").asText())) {
            throw new IllegalArgumentException("syncMode must be BLOCK or ASYNC");
        }
        if (request.has("timeoutSeconds")) {
            JsonNode timeout = request.get("timeoutSeconds");
            if (!timeout.isIntegralNumber() || timeout.intValue() < 1 || timeout.intValue() > 300) {
                throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
            }
        }
        return request;
    }

    private List<ObjectNode> one(ObjectNode request) {
        List<ObjectNode> result = new ArrayList<ObjectNode>();
        result.add(validate(request));
        return result;
    }

    private ObjectNode parseObject(String input) {
        try {
            JsonNode value = mapper.readTree(input);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("Service request must be a JSON object");
            }
            return (ObjectNode) value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid requestDTO JSON", e);
        }
    }

    private BigDecimal decimal(JsonNode value, String field) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("GEO " + field + " must be numeric");
        }
        return value.decimalValue();
    }
}
