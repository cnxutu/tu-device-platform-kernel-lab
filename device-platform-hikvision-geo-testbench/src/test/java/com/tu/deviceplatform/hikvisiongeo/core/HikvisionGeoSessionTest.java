package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HikvisionGeoSessionTest {
    @Test
    void randomSingleSendsOnePointInsideTheRadiusAndPreservesTargetId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<ObjectNode> sentRequest = new AtomicReference<ObjectNode>();
        ServiceInvoker invoker = new ServiceInvoker() {
            @Override public ServiceInvokeResult invoke(ObjectNode request) {
                sentRequest.set(request.deepCopy());
                return new ServiceInvokeResult(200, true, "simulated", "{\"code\":0}", null);
            }
            @Override public boolean isConfigured() { return true; }
            @Override public String getBaseUrl() { return "http://127.0.0.1"; }
        };
        HikvisionGeoSession session = new HikvisionGeoSession(mapper, invoker,
                new HikvisionCommandPreview(new com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec()));
        try {
            session.importRequests(mapper.writeValueAsString(HikvisionGeoCoreTest.request("target_acquire")));
            ObjectNode options = mapper.createObjectNode();
            options.put("index", 0);
            options.put("identifier", "target_follow");
            options.put("longitude", 120.0195387);
            options.put("latitude", 30.2811864);
            options.put("altitude", 18);
            options.put("radiusMeters", 250);

            ObjectNode response = session.sendRandomOnce(options);

            ObjectNode request = sentRequest.get();
            assertNotNull(request);
            assertTrue(response.path("result").path("accepted").asBoolean());
            assertEquals(36, request.path("deviceId").asInt());
            assertEquals("target_follow", request.path("identifier").asText());
            assertEquals("SIM-TARGET-001", request.path("params").path("targetId").asText());
            GeoPointGenerator.Point center = new GeoPointGenerator.Point(
                    new java.math.BigDecimal("120.0195387"), new java.math.BigDecimal("30.2811864"),
                    new java.math.BigDecimal("18"));
            GeoPointGenerator.Point generated = ServiceRequestFactory.point(request);
            assertTrue(GeoPointGenerator.distanceMeters(center, generated) <= 250);
            assertEquals(center.getAltitude(), generated.getAltitude());
        } finally {
            session.close();
        }
    }

    @Test
    void selectingDirectionDoesNotInvokeP2() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        ServiceInvoker invoker = new ServiceInvoker() {
            @Override public ServiceInvokeResult invoke(ObjectNode request) {
                calls.incrementAndGet();
                return new ServiceInvokeResult(200, true, "simulated", "{}", null);
            }
            @Override public boolean isConfigured() { return true; }
            @Override public String getBaseUrl() { return "http://127.0.0.1"; }
        };
        HikvisionGeoSession session = new HikvisionGeoSession(mapper, invoker,
                new HikvisionCommandPreview(new com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec()));
        try {
            ObjectNode options = mapper.createObjectNode();
            options.put("direction", "E");
            options.put("longitude", 120.0195387);
            options.put("latitude", 30.2811864);
            options.put("altitude", 18);
            options.put("radiusMeters", 250);

            ObjectNode selected = session.directionPoint(options);

            assertEquals("E", selected.path("direction").asText());
            assertEquals(0, calls.get());
            assertTrue(selected.path("point").path("longitude").asDouble() > options.path("longitude").asDouble());
            assertEquals(18, selected.path("point").path("altitude").asInt());
        } finally {
            session.close();
        }
    }

    @Test
    void selectingRandomCardinalPointStaysInsideRadiusAndDoesNotInvokeP2() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        ServiceInvoker invoker = new ServiceInvoker() {
            @Override public ServiceInvokeResult invoke(ObjectNode request) {
                calls.incrementAndGet();
                return new ServiceInvokeResult(200, true, "simulated", "{}", null);
            }
            @Override public boolean isConfigured() { return true; }
            @Override public String getBaseUrl() { return "http://127.0.0.1"; }
        };
        HikvisionGeoSession session = new HikvisionGeoSession(mapper, invoker,
                new HikvisionCommandPreview(new com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec()));
        try {
            ObjectNode options = mapper.createObjectNode();
            options.put("longitude", 120.0195387);
            options.put("latitude", 30.2811864);
            options.put("altitude", 18);
            options.put("radiusMeters", 250);
            GeoPointGenerator.Point center = new GeoPointGenerator.Point(
                    new java.math.BigDecimal("120.0195387"), new java.math.BigDecimal("30.2811864"),
                    new java.math.BigDecimal("18"));

            for (String direction : new String[] {"N", "E", "S", "W"}) {
                options.put("direction", direction);
                ObjectNode selected = session.randomCardinalPoint(options);
                GeoPointGenerator.Point point = new GeoPointGenerator.Point(
                        selected.path("point").path("longitude").decimalValue(),
                        selected.path("point").path("latitude").decimalValue(),
                        selected.path("point").path("altitude").decimalValue());
                assertEquals(direction, selected.path("direction").asText());
                assertTrue(GeoPointGenerator.distanceMeters(center, point) <= 250);
                assertTrue(selected.path("distanceMeters").asDouble() <= 250);
            }
            assertEquals(0, calls.get());
        } finally {
            session.close();
        }
    }

    @Test
    void stoppingRandomLoopPreventsTheNextP2Request() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstCall = new CountDownLatch(1);
        ServiceInvoker invoker = new ServiceInvoker() {
            @Override public ServiceInvokeResult invoke(ObjectNode request) {
                calls.incrementAndGet();
                firstCall.countDown();
                return new ServiceInvokeResult(200, true, "simulated", "{\"code\":0}", null);
            }
            @Override public boolean isConfigured() { return true; }
            @Override public String getBaseUrl() { return "http://127.0.0.1"; }
        };
        HikvisionGeoSession session = new HikvisionGeoSession(mapper, invoker,
                new HikvisionCommandPreview(new com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec()));
        try {
            session.importRequests(mapper.writeValueAsString(HikvisionGeoCoreTest.request("target_follow")));
            ObjectNode options = mapper.createObjectNode();
            options.put("index", 0);
            options.put("identifier", "target_follow");
            options.put("mode", "random");
            options.put("longitude", 120.0195387);
            options.put("latitude", 30.2811864);
            options.put("altitude", 15);
            options.put("radiusMeters", 1000);
            options.put("maxDurationSeconds", 30);

            session.startLoop(options);
            assertTrue(firstCall.await(3, TimeUnit.SECONDS));
            ObjectNode stopped = session.stopLoop();
            Thread.sleep(1200);

            assertFalse(stopped.path("running").asBoolean());
            assertEquals(1, calls.get());
            assertEquals(1, session.state().path("sentCount").asInt());
        } finally {
            session.close();
        }
    }
}
