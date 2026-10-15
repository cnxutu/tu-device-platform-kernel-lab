package com.tu.deviceplatform.detectiondebug.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** NT-XR1 protocol payloads for deterministic WGS84 points; altitude is sea level in metres. */
public final class NtXr1Payload {
    private static final ObjectMapper JSON = new ObjectMapper();
    private NtXr1Payload() { }

    /** Validate operator input without inventing missing height datums or changing precision. */
    public static ObjectNode point(JsonNode input) {
        ObjectNode point = JSON.createObjectNode();
        for (String key : new String[]{"longitude", "latitude", "altitude"}) {
            JsonNode value = input.path(key);
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                throw new IllegalArgumentException(key + " must be a finite number");
            }
            point.set(key, value);
        }
        if (Math.abs(point.path("longitude").asDouble()) > 180 || Math.abs(point.path("latitude").asDouble()) > 90) {
            throw new IllegalArgumentException("WGS84 longitude -180..180, latitude -90..90 required");
        }
        if (input.hasNonNull("relativeHeight")) {
            JsonNode height = input.get("relativeHeight");
            if (!height.isNumber() || !Double.isFinite(height.asDouble())) { throw new IllegalArgumentException("Invalid relativeHeight"); }
            point.set("relativeHeight", height);
        }
        return point;
    }

    /** Same topic casing as the existing NT-XR1 simulator. */
    public static String topic(String deviceNo, boolean heartbeat) {
        identifier(deviceNo);
        return "/server/xmrd1/api/" + (heartbeat ? "deviceInfo/" : "LocatedResult/") + deviceNo;
    }

    /** Build a heartbeat; sending it can mark the configured simulation device online. */
    public static ObjectNode heartbeat(String deviceNo, long now) {
        identifier(deviceNo);
        ObjectNode root = JSON.createObjectNode(); root.put("topic", "deviceInfo");
        root.put("createTime", now / 1000); root.put("protocolVersion", "1.0"); root.put("firmwareVersion", "202501");
        ObjectNode info = root.putObject("deviceInfo"); info.put("deviceId", deviceNo); info.put("deviceModel", "NT-XR1");
        info.put("deviceType", 0); info.put("firmwareVersion", "1.0.0"); info.put("gpsLocked", true);
        info.put("heartbeatUnix", now / 1000); info.put("lastReportUnix", now / 1000); info.put("lastDetectUnix", now / 1000);
        info.put("deviceStatus", 0); return root;
    }

    /** Fresh event time on each send; trackId remains stable across point changes. */
    public static ObjectNode locate(String deviceNo, String targetId, JsonNode input, long now) {
        identifier(deviceNo); identifier(targetId); ObjectNode point = point(input);
        ObjectNode root = JSON.createObjectNode(); root.put("topic", "locateResult"); root.put("deviceId", deviceNo);
        root.put("createTime", now / 1000); root.put("protocolVersion", "1.0");
        ObjectNode drone = root.putArray("dronePos").addObject();
        drone.put("version", "GB42590-2023"); drone.put("trackId", targetId);
        drone.put("operationCategoryCode", 1); drone.put("uaCategoryCode", 2); drone.put("controlPosType", 1);
        drone.set("uavLongitude", point.get("longitude")); drone.set("uavLatitude", point.get("latitude"));
        drone.set("uavAltitude", point.get("altitude"));
        if (point.has("relativeHeight")) { drone.set("relativeHeight", point.get("relativeHeight")); }
        // Unavailable pilot, geodetic, AGL and pressure heights are deliberately absent.
        drone.put("coordinateTypeCode", 0); drone.put("runStatusCode", 2); drone.put("timestamp", now);
        drone.put("timestampAccuracyCode", 0); drone.put("horizontalAccuracyCode", 3); drone.put("verticalAccuracyCode", 2);
        drone.put("speedAccuracyCode", 1); drone.put("uavModel", "SIMULATED-UAV"); drone.put("frequency", 2437);
        drone.put("courseAngle", 0); drone.put("groundSpeed", 0); drone.put("verticalSpeed", 0); return root;
    }

    /** Simulation identifiers must not inject MQTT topic separators or wildcards. */
    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Identifier must contain 1..64 letters, digits, underscore or hyphen");
        }
    }
}
