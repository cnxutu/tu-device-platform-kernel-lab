package com.tu.deviceplatform.tofumsweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofums.adapter.TofuMsSimulator;
import com.tu.deviceplatform.tofumsweb.adapter.GatewayWebServer;
import com.tu.deviceplatform.tofumsweb.core.GatewayWebLab;
import com.tu.deviceplatform.tofumsweb.core.LabJournal;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class GatewayWebIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void httpInvokesRealGatewayAndAdapterAndReceivesSeparatePropertiesAckAndResults() throws Exception {
        try (TofuMsSimulator simulator = new TofuMsSimulator(0); GatewayWebLab lab = lab(); GatewayWebServer web = new GatewayWebServer(lab, 0)) {
            assertEquals(200, http(web, "GET", "/", null).code);
            JsonNode catalog = http(web, "GET", "/api/catalog", null).json;
            assertEquals(mapper.readTree(tslPath().toFile()).get("data"), catalog);
            assertEquals(10, catalog.path("properties").size());
            assertEquals(3, catalog.path("services").size()); assertEquals(1, catalog.path("events").size());
            assertFalse(state(web).path("connected").asBoolean()); assertNull(simulator.awaitReceipt(200));
            simulator.setHeartbeatStatusReply(true);
            connect(web, simulator);
            await(web, value -> value.path("online").asBoolean() && value.path("properties").has("panAngle"), 3000);

            assertEquals(200, invoke(web, "ptz_control", 2, 30, 300).code);
            TofuMsSimulator.Receipt move = simulator.awaitReceipt(1000), stop = simulator.awaitReceipt(2000);
            assertEquals("FF010002130016", serial(move)); assertEquals("FF010000000001", serial(stop));
            assertTrue(stop.receivedNanos - move.receivedNanos >= TimeUnit.MILLISECONDS.toNanos(250));
            await(web, value -> !value.path("motionActive").asBoolean(), 1000);
            assertEquals(200, invoke(web, "zoom_control", 1, 50, 1200).code);
            assertEquals("8101040723FF", serial(simulator.awaitReceipt(1000)));
            assertEquals(200, invoke(web, "zoom_control", 0, null, null).code);
            assertEquals("8101040700FF", serial(simulator.awaitReceipt(1000)));
            assertNull(simulator.awaitReceipt(1300)); // Manual STOP cancelled the timer.
            assertEquals(200, invoke(web, "focus_control", 1, null, 300).code);
            assertEquals("8101040802FF", serial(simulator.awaitReceipt(1000)));
            assertEquals("8101040800FF", serial(simulator.awaitReceipt(2000)));
            JsonNode snapshot = await(web, value -> has(value, "ACK"), 2000);
            JsonNode property = first(snapshot, "PROPERTY").get("data");
            assertEquals("thing.property.post", property.path("method").asText());
            assertTrue(property.path("params").has("timestamp")); assertTrue(property.path("params").has("visibleZoom"));
            assertFalse(property.path("params").has("event")); assertFalse(property.path("params").has("deviceTime"));
            assertFalse(snapshot.path("properties").has("infraredZoom"));
            JsonNode ack = first(snapshot, "ACK").get("data");
            assertEquals("thing.event.post", ack.path("method").asText());
            assertEquals("optical_protocol_feedback", ack.path("params").path("identifier").asText());
            assertTrue(ack.path("requestId").isMissingNode() || ack.path("requestId").isNull());
            assertEquals(0, ack.path("params").path("event").path("statusCode").intValue());
            assertEquals(0, snapshot.path("lastAck").path("params").path("event").path("statusCode").intValue());
            Set<String> commands = new HashSet<>(); int delivered = 0;
            for (JsonNode event : snapshot.path("events")) {
                if ("DOWNSTREAM".equals(event.path("type").asText())) commands.add(event.path("data").path("requestId").asText());
                if ("RESULT".equals(event.path("type").asText()) && "SENT".equals(event.path("data").path("data").path("deliveryState").asText())) {
                    assertTrue(commands.contains(event.path("data").path("requestId").asText())); delivered++;
                }
            }
            assertEquals(6, delivered); // Three movements and their STOPs through gateway.onMessage.
            assertTrue(has(snapshot, "TX_FRAME")); assertTrue(has(snapshot, "RX_FRAME"));
            boolean capturedHeartbeatSeen = false;
            for (JsonNode event : snapshot.path("events")) {
                if ("RX_FRAME".equals(event.path("type").asText()) && "EC9111000000020000".equals(event.path("data").path("hex").asText())) {
                    capturedHeartbeatSeen = true;
                }
            }
            assertTrue(capturedHeartbeatSeen);
        }
    }

    @Test void invalidAndOfflineCommandsAreRejectedAndReconnectDoesNotReplayMovement() throws Exception {
        try (TofuMsSimulator simulator = new TofuMsSimulator(0); GatewayWebLab lab = lab(); GatewayWebServer web = new GatewayWebServer(lab, 0)) {
            simulator.setHeartbeatReplies(false); connect(web, simulator);
            assertEquals(400, invoke(web, "ptz_control", 2, 30, 0).code);
            assertEquals(400, invoke(web, "ptz_control", 2, 101, 300).code);
            assertEquals(400, invoke(web, "focus_control", 1, 30, 300).code);
            assertEquals(400, invoke(web, "unknown_service", 1, 30, 300).code);
            assertEquals(400, http(web, "POST", "/api/invoke", "{\"identifier\":\"ptz_control\",\"params\":{\"channelId\":\"1\",\"Action\":2},\"durationMillis\":300}").code);
            assertEquals(409, invoke(web, "ptz_control", 2, 30, 300).code);
            assertNull(simulator.awaitReceipt(200));
            simulator.setHeartbeatReplies(true); await(web, value -> value.path("online").asBoolean(), 3000);
            assertNull(simulator.awaitReceipt(200));
            assertEquals(200, invoke(web, "ptz_control", 2, 30, 500).code);
            assertNotNull(simulator.awaitReceipt(1000)); simulator.disconnectClients();
            await(web, value -> !value.path("online").asBoolean(), 1000);
            await(web, value -> hasControl(value, "STOP_FAILED"), 1200);
            assertTrue(state(web).path("motionActive").asBoolean());
            await(web, value -> value.path("online").asBoolean(), 3000);
            assertNull(simulator.awaitReceipt(250)); // Neither movement nor a stale timer replayed.
            assertEquals(409, invoke(web, "ptz_control", 1, 30, 300).code);
            assertEquals(200, invoke(web, "ptz_control", 0, null, null).code);
            assertEquals("FF010000000001", serial(simulator.awaitReceipt(1000)));
            assertFalse(state(web).path("motionActive").asBoolean());
        }
    }

    @Test void browserTabsShareReservationAndDisconnectStopsBeforeClosingTcp() throws Exception {
        try (TofuMsSimulator simulator = new TofuMsSimulator(0); GatewayWebLab lab = lab(); GatewayWebServer web = new GatewayWebServer(lab, 0)) {
            connect(web, simulator); await(web, value -> value.path("online").asBoolean(), 3000);
            ExecutorService browsers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Callable<Integer> command = () -> { start.await(); return invoke(web, "ptz_control", 2, 30, 2000).code; };
                Future<Integer> tabOne = browsers.submit(command), tabTwo = browsers.submit(command); start.countDown();
                List<Integer> codes = Arrays.asList(tabOne.get(3, TimeUnit.SECONDS), tabTwo.get(3, TimeUnit.SECONDS));
                assertTrue(codes.contains(200)); assertTrue(codes.contains(409));
                assertEquals("FF010002130016", serial(simulator.awaitReceipt(1000)));
                assertNull(simulator.awaitReceipt(150));
                assertEquals(200, http(web, "POST", "/api/disconnect", "{}").code);
                assertEquals("FF010000000001", serial(simulator.awaitReceipt(1000)));
                assertEquals("8101040700FF", serial(simulator.awaitReceipt(1000)));
                assertEquals("8101040800FF", serial(simulator.awaitReceipt(1000)));
                assertFalse(state(web).path("connected").asBoolean());
                assertEquals(0, simulator.getClientCount());
                assertEquals(409, invoke(web, "ptz_control", 2, 30, 300).code);
                assertNull(simulator.awaitReceipt(2200));
            } finally { browsers.shutdownNow(); }
        }
    }

    @Test void disconnectFailureIsVisibleAndRetainsTheActiveSessionForStopRetry() throws Exception {
        try (TofuMsSimulator simulator = new TofuMsSimulator(0); GatewayWebLab lab = lab(); GatewayWebServer web = new GatewayWebServer(lab, 0)) {
            connect(web, simulator); await(web, value -> value.path("online").asBoolean(), 3000);
            assertEquals(200, invoke(web, "ptz_control", 2, 30, 2000).code);
            assertNotNull(simulator.awaitReceipt(1000)); simulator.disconnectClients();
            await(web, value -> !value.path("online").asBoolean(), 1000);
            assertEquals(409, http(web, "POST", "/api/disconnect", "{}").code);
            assertTrue(state(web).path("connected").asBoolean()); assertTrue(state(web).path("motionActive").asBoolean());
            await(web, value -> value.path("online").asBoolean(), 3000);
            assertEquals(200, http(web, "POST", "/api/stop", "{}").code);
            assertEquals("FF010000000001", serial(simulator.awaitReceipt(1000)));
            assertEquals("8101040700FF", serial(simulator.awaitReceipt(1000)));
            assertEquals("8101040800FF", serial(simulator.awaitReceipt(1000)));
        }
    }

    @Test void journalBoundsAndFreezesObservationsAndHttpRejectsCrossOriginMutations() throws Exception {
        LabJournal journal = new LabJournal(mapper); Map<String, Object> mutable = new HashMap<>(); mutable.put("angle", 1);
        journal.add("PROPERTY", mutable); mutable.put("angle", 2);
        assertEquals(1, mapper.valueToTree(journal.since(0)).path("events").get(0).path("data").path("angle").intValue());
        for (int i = 0; i < 1001; i++) journal.add("PROPERTY", i);
        JsonNode snapshot = mapper.valueToTree(journal.since(0)); assertEquals(1000, snapshot.path("events").size()); assertTrue(snapshot.path("truncated").asBoolean());
        assertEquals(0, mapper.valueToTree(journal.since(snapshot.path("cursor").longValue())).path("events").size());
        try (GatewayWebLab lab = lab(); GatewayWebServer web = new GatewayWebServer(lab, 0)) {
            // Java 8 HttpURLConnection suppresses Origin; send the actual header over a socket.
            try (Socket connection = new Socket("127.0.0.1", web.port())) {
                connection.setSoTimeout(2000);
                connection.getOutputStream().write(("POST /api/stop HTTP/1.1\r\nHost: 127.0.0.1:" + web.port()
                        + "\r\nOrigin: http://unrelated.invalid\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                        .getBytes(StandardCharsets.UTF_8));
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                assertTrue(input.readLine().startsWith("HTTP/1.1 403"));
            }
            assertEquals(400, http(web, "POST", "/api/connect", "{broken").code);
            assertFalse(state(web).path("connected").asBoolean());
        }
    }

    private GatewayWebLab lab() throws IOException { return new GatewayWebLab(tslPath()); }
    private Path tslPath() {
        String path = System.getProperty("tofu.tsl.path");
        assertNotNull(path, "Pass -Dtofu.tsl.path=<current TOFU-MS.json> to validate the actual model contract");
        return Paths.get(path);
    }
    private void connect(GatewayWebServer web, TofuMsSimulator simulator) throws Exception {
        Reply reply = http(web, "POST", "/api/connect", "{\"host\":\"127.0.0.1\",\"port\":" + simulator.getPort() + ",\"ptzAddress\":1}");
        assertEquals(200, reply.code, reply.json.toString()); assertTrue(reply.json.path("connected").asBoolean());
        assertTrue(reply.json.hasNonNull("syncResult"));
    }
    private Reply invoke(GatewayWebServer web, String identifier, int action, Integer speed, Integer duration) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>(); params.put("channelId", "1"); params.put("action", action);
        if (speed != null) params.put("speedPercent", speed);
        Map<String, Object> body = new LinkedHashMap<>(); body.put("identifier", identifier); body.put("params", params);
        if (duration != null) body.put("durationMillis", duration);
        return http(web, "POST", "/api/invoke", mapper.writeValueAsString(body));
    }
    private JsonNode state(GatewayWebServer web) throws IOException { return http(web, "GET", "/api/state", null).json; }
    private JsonNode await(GatewayWebServer web, Predicate<JsonNode> condition, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); JsonNode last;
        do { last = state(web); if (condition.test(last)) return last; Thread.sleep(15); } while (System.nanoTime() < deadline);
        fail("State timed out: " + last); return last;
    }
    private boolean has(JsonNode state, String type) { return first(state, type) != null; }
    private boolean hasControl(JsonNode state, String text) {
        for (JsonNode event : state.path("events")) if ("CONTROL".equals(event.path("type").asText()) && event.path("data").asText().contains(text)) return true;
        return false;
    }
    private JsonNode first(JsonNode state, String type) {
        for (JsonNode event : state.path("events")) if (type.equals(event.path("type").asText())) return event;
        return null;
    }
    private String serial(TofuMsSimulator.Receipt receipt) { assertNotNull(receipt); return receipt.payload.path("SerialData").path("Data").asText(); }
    private Reply http(GatewayWebServer web, String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + web.port() + path).openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(15000); connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = connection.getResponseCode();
        try (InputStream input = code < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            String text = output.toString("UTF-8");
            return new Reply(code, path.equals("/") ? mapper.getNodeFactory().textNode(text) : mapper.readTree(text));
        } finally { connection.disconnect(); }
    }
    private static final class Reply { final int code; final JsonNode json; Reply(int code, JsonNode json) { this.code = code; this.json = json; } }
}
