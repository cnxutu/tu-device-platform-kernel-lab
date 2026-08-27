package com.tu.deviceplatform.thingmodel.core;

import java.util.Objects;

/**
 * 物模型的最小校验边界：名称、交互类型和载荷类型必须都匹配。
 */
public final class ThingModelKernel {
    public void accept(ThingModel model, StandardMessage message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");

        Capability capability = model.capabilityOf(message.getIdentifier());
        if (capability == null) {
            throw new IllegalArgumentException("unknown capability: " + message.getIdentifier());
        }
        if (capability.getType() != message.getType()) {
            throw new IllegalArgumentException("capability type mismatch: " + message.getIdentifier());
        }
        if (!capability.getValueType().isInstance(message.getValue())) {
            throw new IllegalArgumentException("value type mismatch: " + message.getIdentifier());
        }
    }
}

