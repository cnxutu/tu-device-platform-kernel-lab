package com.tu.deviceplatform.thingmodel.adapter.djidock;

import com.tu.deviceplatform.thingmodel.core.CapabilityType;
import com.tu.deviceplatform.thingmodel.core.StandardMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将简化的 DJI Dock 风格消息转换为与厂商无关的标准消息。
 */
public final class DjiDockAdapter {
    public StandardMessage decodeOsd(String topic, Map<String, Object> payload) {
        String deviceId = deviceIdFrom(topic, "osd");
        Object batteryPercent = required(payload, "battery_percent");

        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("sourceTopic", topic);
        attributes.put("flightMode", payload.get("mode_code"));
        return new StandardMessage(deviceId, "battery_percent", CapabilityType.PROPERTY,
                batteryPercent, attributes);
    }

    public StandardMessage decodeService(String topic, String serviceId, Map<String, Object> params) {
        String deviceId = deviceIdFrom(topic, "services");
        Map<String, Object> attributes = new LinkedHashMap<String, Object>(params);
        attributes.put("sourceTopic", topic);
        return new StandardMessage(deviceId, serviceId, CapabilityType.SERVICE,
                Collections.unmodifiableMap(new LinkedHashMap<String, Object>(params)), attributes);
    }

    private static String deviceIdFrom(String topic, String expectedLastPart) {
        if (topic == null) {
            throw new IllegalArgumentException("topic must not be null");
        }
        String[] parts = topic.split("/");
        if (parts.length != 4 || !"thing".equals(parts[0]) || !"product".equals(parts[1])
                || !expectedLastPart.equals(parts[3]) || parts[2].trim().isEmpty()) {
            throw new IllegalArgumentException("unsupported topic: " + topic);
        }
        return parts[2];
    }

    private static Object required(Map<String, Object> payload, String key) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("missing payload field: " + key);
        }
        return payload.get(key);
    }
}

