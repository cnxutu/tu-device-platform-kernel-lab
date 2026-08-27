package com.tu.deviceplatform.thingmodel.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 某类设备的能力字典。这里使用内存模型，便于观察核心约束。
 */
public final class ThingModel {
    private final String modelId;
    private final Map<String, Capability> capabilities = new LinkedHashMap<String, Capability>();

    public ThingModel(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        this.modelId = modelId;
    }

    public ThingModel add(Capability capability) {
        Capability checked = Objects.requireNonNull(capability, "capability");
        Capability previous = capabilities.put(checked.getIdentifier(), checked);
        if (previous != null) {
            capabilities.put(previous.getIdentifier(), previous);
            throw new IllegalArgumentException("duplicate capability: " + checked.getIdentifier());
        }
        return this;
    }

    public String getModelId() {
        return modelId;
    }

    public Capability capabilityOf(String identifier) {
        return capabilities.get(identifier);
    }

    public Map<String, Capability> getCapabilities() {
        return Collections.unmodifiableMap(capabilities);
    }
}

