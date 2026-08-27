package com.tu.deviceplatform.thingmodel.core;

import java.util.Objects;

/**
 * 一个可被设备上报或调用的物模型能力。
 */
public final class Capability {
    private final String identifier;
    private final CapabilityType type;
    private final Class<?> valueType;

    public Capability(String identifier, CapabilityType type, Class<?> valueType) {
        this.identifier = requireText(identifier, "identifier");
        this.type = Objects.requireNonNull(type, "type");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public String getIdentifier() {
        return identifier;
    }

    public CapabilityType getType() {
        return type;
    }

    public Class<?> getValueType() {
        return valueType;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

