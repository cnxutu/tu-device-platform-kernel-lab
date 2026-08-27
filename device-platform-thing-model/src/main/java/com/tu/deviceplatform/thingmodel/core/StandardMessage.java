package com.tu.deviceplatform.thingmodel.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 协议适配之后进入平台内核的统一消息。
 */
public final class StandardMessage {
    private final String deviceId;
    private final String identifier;
    private final CapabilityType type;
    private final Object value;
    private final Map<String, Object> attributes;

    public StandardMessage(String deviceId, String identifier, CapabilityType type, Object value,
                           Map<String, Object> attributes) {
        this.deviceId = requireText(deviceId, "deviceId");
        this.identifier = requireText(identifier, "identifier");
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(
                Objects.requireNonNull(attributes, "attributes")));
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getIdentifier() {
        return identifier;
    }

    public CapabilityType getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

