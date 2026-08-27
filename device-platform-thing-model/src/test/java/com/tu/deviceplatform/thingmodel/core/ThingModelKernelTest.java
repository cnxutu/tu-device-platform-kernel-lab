package com.tu.deviceplatform.thingmodel.core;

import com.tu.deviceplatform.thingmodel.adapter.djidock.DjiDockAdapter;
import com.tu.deviceplatform.thingmodel.scenario.ThingModelDemo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThingModelKernelTest {
    private final ThingModelKernel kernel = new ThingModelKernel();
    private final ThingModel model = ThingModelDemo.dockModel();

    @Test
    void shouldAcceptAdaptedOsdProperty() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("battery_percent", 72);
        payload.put("mode_code", 0);

        StandardMessage message = new DjiDockAdapter()
                .decodeOsd("thing/product/DEMO-DOCK-001/osd", payload);

        assertDoesNotThrow(() -> kernel.accept(model, message));
    }

    @Test
    void shouldRejectMismatchedCapabilityType() {
        StandardMessage message = new StandardMessage("DEMO-DOCK-001", "battery_percent",
                CapabilityType.SERVICE, 72, new LinkedHashMap<String, Object>());

        assertThrows(IllegalArgumentException.class, () -> kernel.accept(model, message));
    }
}

