package com.tu.deviceplatform.projectionreplay.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectionCalculatorTest {
    @Test
    void matchesP1PlanarCenterAndCameraCornerOrder() {
        ProjectionCalculator calculator = new ProjectionCalculator(60, 40, -60, 1, 0, 0, 1);
        Map<String, Object> geometry = calculator.calculate(frame(1000, -90, 90));
        assertEquals("PLANAR", geometry.get("viewMode"));
        assertEquals("CAMERA_TL_TR_BR_BL", geometry.get("cornerOrder"));
        assertEquals(2, geometry.get("geometryVersion"));
        assertNotNull(geometry.get("frameCenter"));
        assertEquals(4, ((List<?>) geometry.get("corners")).size());
        assertNull(geometry.get("spatialCenter"));
    }

    @Test
    void keepsP1ModeHysteresisWithEventTime() {
        ProjectionCalculator calculator = new ProjectionCalculator(60, 40, -60, 1, 0, 0, 1);
        assertEquals("PLANAR", calculator.calculate(frame(1000, -60, 90)).get("viewMode"));
        assertEquals("PLANAR", calculator.calculate(frame(1100, -59.9, 90)).get("viewMode"));
        assertEquals("SPATIAL", calculator.calculate(frame(1200, -58.9, 90)).get("viewMode"));
        assertEquals("SPATIAL", calculator.calculate(frame(1300, -59.9, 90)).get("viewMode"));
        assertEquals("PLANAR", calculator.calculate(frame(1400, -60, 90)).get("viewMode"));
    }

    @Test
    void rejectsOutOfRangePitch() {
        ProjectionCalculator calculator = new ProjectionCalculator(60, 40, -60, 1, 0, 0, 1);
        assertNull(calculator.calculate(frame(1000, -92.1, 90)));
    }

    private OsdFrame frame(long time, double pitch, double yaw) {
        return new OsdFrame(time, "DEMO-DRONE", new BigDecimal("30.1"), new BigDecimal("120.2"),
                40f, 90f, pitch, 0d, yaw);
    }
}
