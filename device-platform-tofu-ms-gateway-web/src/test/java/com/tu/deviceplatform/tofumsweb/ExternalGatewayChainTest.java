package com.tu.deviceplatform.tofumsweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofums.adapter.TofuMsSimulator;
import com.tu.deviceplatform.tofumsweb.adapter.GatewayWebServer;
import com.tu.deviceplatform.tofumsweb.core.GatewayWebLab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Real Redis and a separate P3 JVM; only the platform and device are simulated. */
@EnabledIfSystemProperty(named = "tofu.gateway.jar", matches = ".+")
class ExternalGatewayChainTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Process gateway;
    private int gatewayIdentity;
    private Path log;

    @Test void separateGatewayControlsDeviceAndCannotBeBypassedAcrossRestartOrModeSwitch() throws Exception {
        gatewayIdentity = freePort();
        log = Paths.get("target/external-gateway-chain.log").toAbsolutePath();
        try (TofuMsSimulator simulator = new TofuMsSimulator(0);
             GatewayWebLab lab = new GatewayWebLab(Paths.get(System.getProperty("tofu.tsl.path")))) {
            lab.enableRemote(Integer.parseInt(System.getProperty("tofu.redis.port", "16379")));
            try (GatewayWebServer web = new GatewayWebServer(lab, 0)) {
                simulator.setHeartbeatStatusReply(true);
                startGateway(web);
                await(web, s -> s.path("gateway").path("ready").asBoolean(), 15000);
                assertEquals("ws-tofu-ms-v", state(web).path("gateway").path("protocolCodecId").asText());
                connect(web, simulator, "remote");
                await(web, s -> s.path("online").asBoolean() && s.path("properties").has("panAngle"), 8000);
                assertEquals(1, simulator.getClientCount());

                assertEquals(400, invoke(web, "ptz_control", 2, 101, 300).code);
                assertEquals(400, invoke(web, "optical_focus_control", 1, 30, 300).code);
                assertNull(simulator.awaitReceipt(200));
                motion(web, simulator, "ptz_control", 2, 30, "FF010002130016", "FF010000000001");
                motion(web, simulator, "zoom_control", 1, 100, "8101040727FF", "8101040700FF");
                motion(web, simulator, "optical_focus_control", 1, null, "8101040802FF", "8101040800FF");
                JsonNode evidence = await(web, s -> has(s, "ACK") && has(s, "TX_FRAME") && has(s, "RX_FRAME"), 8000);
                mapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/external-gateway-evidence.json").toFile(), evidence);
                assertEquals("optical_protocol_feedback", evidence.path("lastAck").path("params").path("identifier").asText());
                // P3 forwarding assigns a message ID when the device supplies none. It is not a command correlation.
                for (JsonNode event : evidence.path("events")) {
                    if ("DOWNSTREAM".equals(event.path("type").asText())) {
                        assertNotEquals(event.path("data").path("requestId"), evidence.path("lastAck").path("requestId"));
                    }
                }
                assertFalse(evidence.path("properties").has("event"));
                int delivered = 0;
                for (JsonNode event : evidence.path("events")) {
                    if ("RESULT".equals(event.path("type").asText()) && "SENT".equals(event.path("data").path("data").path("deliveryState").asText())) {
                        assertTrue(event.path("data").hasNonNull("requestId")); delivered++;
                    }
                }
                assertEquals(6, delivered);

                // Manual STOP cancels the timer, including in remote mode.
                assertEquals(200, invoke(web, "ptz_control", 1, 100, 2000).code);
                assertEquals("FF0100043F0044", serial(simulator.awaitReceipt(3000)));
                assertEquals(200, invoke(web, "ptz_control", 0, null, null).code);
                assertEquals("FF010000000001", serial(simulator.awaitReceipt(3000)));
                assertNull(simulator.awaitReceipt(2200));

                // A second connection cannot instantiate an embedded path alongside remote.
                assertEquals(409, http(web, "POST", "/api/connect", target(simulator, "embedded")).code);
                assertEquals(1, simulator.getClientCount());
                disconnect(web, simulator);
                connect(web, simulator, "embedded"); await(web, s -> s.path("online").asBoolean(), 5000);
                motion(web, simulator, "ptz_control", 2, 30, "FF010002130016", "FF010000000001");
                disconnect(web, simulator);
                connect(web, simulator, "remote"); await(web, s -> s.path("online").asBoolean(), 8000);

                // Kill P3 during movement: its device socket must close; P10 cannot replace it.
                assertEquals(200, invoke(web, "ptz_control", 2, 30, 1000).code);
                assertEquals("FF010002130016", serial(simulator.awaitReceipt(3000)));
                long generation = state(web).path("gateway").path("generation").asLong();
                killGateway();
                await(web, s -> !s.path("gateway").path("ready").asBoolean(), 15000);
                assertEquals(0, simulator.getClientCount());
                assertEquals(409, invoke(web, "ptz_control", 2, 30, 300).code);
                assertNull(simulator.awaitReceipt(300));
                startGateway(web);
                await(web, s -> s.path("gateway").path("ready").asBoolean()
                        && s.path("gateway").path("generation").asLong() > generation, 15000);
                assertNull(simulator.awaitReceipt(500));
                assertEquals(0, simulator.getClientCount(), "Restart must not restore a target or replay movement automatically");
                assertEquals(409, invoke(web, "ptz_control", 2, 30, 300).code);
                assertEquals(200, http(web, "POST", "/api/stop", "{}").code);
                assertEquals("FF010000000001", serial(simulator.awaitReceipt(3000)));
                assertEquals("8101040700FF", serial(simulator.awaitReceipt(3000)));
                assertEquals("8101040800FF", serial(simulator.awaitReceipt(3000)));
                assertFalse(state(web).path("motionActive").asBoolean());
                disconnect(web, simulator);
                connect(web, simulator, "remote"); await(web, s -> s.path("online").asBoolean(), 8000);
                motion(web, simulator, "ptz_control", 2, 30, "FF010002130016", "FF010000000001");
                disconnect(web, simulator);
                System.out.println("EXTERNAL_P3_CHAIN_PASSED: Redis / config sync / TCP / PTZ zoom focus / STOP / property ACK result / modes / kill restart no replay");
            } finally { killGateway(); }
        }
    }
    private void startGateway(GatewayWebServer web) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dloader.main=com.xmkj.common.iot.gateway.tofums.TofuMsStandaloneApplication", "-cp",
                Paths.get(System.getProperty("tofu.gateway.jar")).toAbsolutePath().toString(),
                "org.springframework.boot.loader.PropertiesLauncher", "--server.port=" + gatewayIdentity,
                "--star.iot.gateway.protocol.customs.tofuMsCustomProtocolAdapter.port=" + gatewayIdentity);
        builder.environment().put("TOFU_PLATFORM_PORT", String.valueOf(web.port()));
        builder.environment().put("TOFU_REDIS_PORT", System.getProperty("tofu.redis.port", "16379"));
        gateway = builder.directory(log.getParent().toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }
    private void killGateway() throws InterruptedException {
        if (gateway != null && gateway.isAlive()) { gateway.destroyForcibly(); assertTrue(gateway.waitFor(5, TimeUnit.SECONDS)); }
    }
    private int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private void motion(GatewayWebServer web, TofuMsSimulator simulator, String id, int action, Integer speed, String move, String stop) throws Exception {
        assertEquals(200, invoke(web, id, action, speed, 300).code);
        TofuMsSimulator.Receipt first = simulator.awaitReceipt(3000), second = simulator.awaitReceipt(4000);
        assertEquals(move, serial(first)); assertEquals(stop, serial(second));
        assertTrue(second.receivedNanos - first.receivedNanos >= TimeUnit.MILLISECONDS.toNanos(250));
        await(web, s -> !s.path("motionActive").asBoolean(), 5000);
    }
    private String serial(TofuMsSimulator.Receipt receipt) { assertNotNull(receipt); return receipt.payload.path("SerialData").path("Data").asText(); }
    private String target(TofuMsSimulator simulator, String mode) { return "{\"host\":\"127.0.0.1\",\"port\":" + simulator.getPort() + ",\"ptzAddress\":1,\"mode\":\"" + mode + "\"}"; }
    private void connect(GatewayWebServer web, TofuMsSimulator simulator, String mode) throws IOException {
        Reply reply = http(web, "POST", "/api/connect", target(simulator, mode)); assertEquals(200, reply.code, reply.json.toString());
    }
    private void disconnect(GatewayWebServer web, TofuMsSimulator simulator) throws Exception {
        Reply reply = http(web, "POST", "/api/disconnect", "{}"); assertEquals(200, reply.code, reply.json.toString());
        assertEquals("FF010000000001", serial(simulator.awaitReceipt(3000)));
        assertEquals("8101040700FF", serial(simulator.awaitReceipt(3000)));
        assertEquals("8101040800FF", serial(simulator.awaitReceipt(3000)));
        assertEquals(0, simulator.getClientCount());
    }
    private Reply invoke(GatewayWebServer web, String id, int action, Integer speed, Integer duration) throws IOException {
        return http(web, "POST", "/api/invoke", "{\"identifier\":\"" + id + "\",\"params\":{\"channelId\":\"1\",\"action\":" + action
                + (speed == null ? "" : ",\"speedPercent\":" + speed) + "}" + (duration == null ? "" : ",\"durationMillis\":" + duration) + "}");
    }
    private boolean has(JsonNode state, String type) { for (JsonNode event : state.path("events")) if (type.equals(event.path("type").asText())) return true; return false; }
    private JsonNode state(GatewayWebServer web) throws IOException { return http(web, "GET", "/api/state", null).json; }
    private JsonNode await(GatewayWebServer web, Predicate<JsonNode> predicate, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout; JsonNode last;
        do { last = state(web); if (predicate.test(last)) return last; Thread.sleep(30); } while (System.currentTimeMillis() < end);
        fail("State timeout; P3 log: " + log + "; state: " + last); return last;
    }
    private Reply http(GatewayWebServer web, String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + web.port() + path).openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(25000); connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = connection.getResponseCode();
        try (InputStream input = code < 400 ? connection.getInputStream() : connection.getErrorStream()) { return new Reply(code, mapper.readTree(input)); }
        finally { connection.disconnect(); }
    }
    private static final class Reply { final int code; final JsonNode json; Reply(int code, JsonNode json) { this.code = code; this.json = json; } }
}
