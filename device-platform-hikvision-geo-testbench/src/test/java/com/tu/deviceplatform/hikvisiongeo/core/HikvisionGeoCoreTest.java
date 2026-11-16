package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.hikvisiongeo.core.GeoPointGenerator.Point;
import com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HikvisionGeoCoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRequestDtoFromServiceLogAndPreservesTheP2Contract() throws Exception {
        ObjectNode source = request("target_acquire");
        String log = "2026-10-22 INFO IOT_DOWN requestDTO:" + mapper.writeValueAsString(source);

        List<ObjectNode> parsed = new ServiceRequestLoader(mapper).parse(log);

        assertEquals(1, parsed.size());
        assertEquals(36, parsed.get(0).path("deviceId").asInt());
        assertEquals("target_acquire", parsed.get(0).path("identifier").asText());
        assertEquals("SIM-TARGET-001", parsed.get(0).path("params").path("targetId").asText());
        assertEquals("BLOCK", parsed.get(0).path("syncMode").asText());
    }

    @Test
    void generatesCardinalAndUniformAreaPointsInsideOneKilometerCircle() {
        Point center = new Point(new BigDecimal("120.0195387"), new BigDecimal("30.2811864"), new BigDecimal("15"));
        List<Point> points = GeoPointGenerator.cardinal(center, 1000);

        assertEquals(4, points.size());
        assertTrue(points.get(0).getLatitude().compareTo(center.getLatitude()) > 0);
        assertTrue(points.get(1).getLongitude().compareTo(center.getLongitude()) > 0);
        assertTrue(points.get(2).getLatitude().compareTo(center.getLatitude()) < 0);
        assertTrue(points.get(3).getLongitude().compareTo(center.getLongitude()) < 0);
        for (Point point : points) {
            assertTrue(Math.abs(GeoPointGenerator.distanceMeters(center, point) - 1000) < 1.0);
            assertEquals(center.getAltitude(), point.getAltitude());
        }

        Random areaRandom = new Random(41);
        for (int i = 0; i < 1000; i++) {
            Point random = GeoPointGenerator.randomWithin(center, 1000, areaRandom);
            assertTrue(GeoPointGenerator.distanceMeters(center, random) <= 1000);
            assertEquals(center.getAltitude(), random.getAltitude());
        }
        assertThrows(IllegalArgumentException.class, () -> GeoPointGenerator.validateRadius(1001));
    }

    @Test
    void generatesAllEightDirectionsOnTheConfiguredRadius() {
        Point center = new Point(new BigDecimal("120.0195387"), new BigDecimal("30.2811864"), new BigDecimal("15"));
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        for (String direction : directions) {
            Point point = GeoPointGenerator.direction(center, 250, direction);
            assertTrue(Math.abs(GeoPointGenerator.distanceMeters(center, point) - 250) < 0.1, direction);
            assertEquals(center.getAltitude(), point.getAltitude(), direction);
        }
    }

    @Test
    void generatesRandomCardinalPointsOnTheSelectedAxisWithoutExceedingRadius() {
        Point center = new Point(new BigDecimal("120.0195387"), new BigDecimal("30.2811864"), new BigDecimal("15"));
        String[] directions = {"N", "E", "S", "W"};
        Random random = new Random(41);

        for (String direction : directions) {
            for (int i = 0; i < 1000; i++) {
                Point point = GeoPointGenerator.randomAlongCardinal(center, 250, direction, random);
                assertTrue(GeoPointGenerator.distanceMeters(center, point) <= 250, direction);
                assertEquals(center.getAltitude(), point.getAltitude(), direction);
                if ("N".equals(direction) || "S".equals(direction)) {
                    assertEquals(center.getLongitude(), point.getLongitude(), direction);
                    assertTrue("N".equals(direction)
                            ? point.getLatitude().compareTo(center.getLatitude()) >= 0
                            : point.getLatitude().compareTo(center.getLatitude()) <= 0, direction);
                } else {
                    assertEquals(center.getLatitude(), point.getLatitude(), direction);
                    assertTrue("E".equals(direction)
                            ? point.getLongitude().compareTo(center.getLongitude()) >= 0
                            : point.getLongitude().compareTo(center.getLongitude()) <= 0, direction);
                }
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> GeoPointGenerator.randomAlongCardinal(center, 250, "NE", random));
    }

    @Test
    void generatedRequestKeepsTargetIdAndOtherP1Fields() {
        ObjectNode original = request("target_follow");
        Point replacement = new Point(new BigDecimal("120.02"), new BigDecimal("30.29"), new BigDecimal("18"));

        ObjectNode generated = ServiceRequestFactory.withPoint(original, "target_follow", replacement);

        assertEquals("SIM-TARGET-001", generated.path("params").path("targetId").asText());
        assertEquals(120.02, generated.path("params").path("position").path("geo").path("longitude").asDouble());
        assertEquals(44.5, generated.path("params").path("position").path("pt").path("azimuth").asDouble(), 0.0001);
        assertEquals("BLOCK", generated.path("syncMode").asText());
        assertFalse(original.path("params").path("position").path("geo").path("longitude").asText().equals("120.02"));
    }

    @Test
    void previewUsesP4HikvisionGeoXmlMappingForBothOperations() {
        HikvisionCommandPreview preview = new HikvisionCommandPreview(new HikvisionXmlCodec());
        ObjectNode acquire = request("target_acquire");
        ObjectNode follow = request("target_follow");

        String acquireXml = preview.create(acquire).getXml();
        String followXml = preview.create(follow).getXml();

        assertTrue(acquireXml.contains("<locationMode>GPS</locationMode>"));
        assertTrue(acquireXml.contains("<isContinuousTrackingEnabled>false</isContinuousTrackingEnabled>"));
        assertTrue(acquireXml.contains("<id>1</id>"));
        assertTrue(followXml.contains("<locationMode>GPS</locationMode>"));
        assertTrue(followXml.contains("<isContinuousTrackingEnabled>true</isContinuousTrackingEnabled>"));
        assertTrue(followXml.contains("<id>123</id>"));
        assertTrue(preview.create(follow).getCurl().contains("$HIKVISION_DEVICE_BASE_URL/ISAPI/PTZCtrl/channels/1/absoluteEx"));
    }

    static ObjectNode request(String identifier) {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("deviceId", 36);
        request.put("identifier", identifier);
        ObjectNode params = request.putObject("params");
        ObjectNode position = params.putObject("position");
        ObjectNode geo = position.putObject("geo");
        geo.put("altitude", 15.0);
        geo.put("latitude", 30.2811864);
        geo.put("longitude", 120.0195387);
        ObjectNode pt = position.putObject("pt");
        pt.put("azimuth", 44.5);
        pt.put("elevation", -78.6);
        pt.put("objectDistance", 913.8);
        params.put("targetId", "SIM-TARGET-001");
        request.put("syncMode", "BLOCK");
        return request;
    }
}
