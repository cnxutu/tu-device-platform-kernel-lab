package com.tu.deviceplatform.tofumsweb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofumsclient.core.TimedOpticalControl;
import com.tu.deviceplatform.tofumsweb.adapter.GatewayControlTransport;
import com.tu.deviceplatform.tofumsweb.adapter.LabControlTransport;
import com.tu.deviceplatform.tofumsweb.adapter.RemoteGatewayPlatform;
import com.tu.deviceplatform.tofumsweb.adapter.RemoteGatewayControlTransport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** One target and one shared control state for all browser tabs. */
public final class GatewayWebLab implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JsonNode catalog;
    private final LabJournal journal = new LabJournal(mapper);
    private LabControlTransport transport;
    private volatile RemoteGatewayPlatform platform;
    private String mode = "embedded";
    private TimedOpticalControl control;
    private boolean closed;

    public GatewayWebLab(Path tslFile) throws IOException {
        JsonNode root = mapper.readTree(tslFile.toFile());
        catalog = root.has("data") ? root.get("data") : root;
        if (!"TOFU-MS".equals(catalog.path("productKey").asText()) || !catalog.path("properties").isArray()
                || !catalog.path("events").isArray() || !catalog.path("services").isArray()) {
            throw new IllegalArgumentException("TOFU-MS TSL properties/services/events required");
        }
        Set<String> services = new HashSet<>();
        catalog.path("services").forEach(service -> services.add(service.path("identifier").asText()));
        if (!services.equals(new HashSet<>(Arrays.asList("ptz_control", "zoom_control", "focus_control")))) {
            throw new IllegalArgumentException("TSL services do not match supported optical controls");
        }
        journal.add("LAB", "Gateway lab started; choose embedded or standalone P3 mode; video is independent");
    }

    public ObjectMapper mapper() { return mapper; }
    public JsonNode catalog() { return catalog.deepCopy(); }
    public synchronized void enableRemote(int redisPort) {
        requireOpen();
        if (platform != null) { throw new IllegalStateException("Remote platform already enabled"); }
        platform = new RemoteGatewayPlatform(mapper, redisPort);
    }
    public Object platformRpc(String path, JsonNode body) {
        requireOpen();
        if (platform == null) { throw new IllegalStateException("Remote platform is not enabled"); }
        if ("/rpc-api/iot/gateway/register".equals(path)) { return platform.register(body); }
        if ("/rpc-api/iot/device/get".equals(path)) { return platform.device(body); }
        throw new IllegalArgumentException("Unsupported simulated platform RPC");
    }

    public synchronized Map<String, Object> connect(JsonNode body) {
        requireOpen(); fields(body, "host", "port", "ptzAddress", "mode");
        if (transport != null) { throw new IllegalStateException("Disconnect the current target before connecting another"); }
        if (!body.path("host").isTextual() || body.path("host").asText().trim().isEmpty()) { throw new IllegalArgumentException("Target host required"); }
        int port = integer(body, "port"), address = integer(body, "ptzAddress");
        if (port < 1 || port > 65535 || address < 1 || address > 255) { throw new IllegalArgumentException("Port 1..65535 and PTZ address 1..255 required"); }
        String selectedMode = body.has("mode") ? body.path("mode").asText() : "embedded";
        if (!"embedded".equals(selectedMode) && !"remote".equals(selectedMode)) { throw new IllegalArgumentException("mode must be embedded or remote"); }
        if ("remote".equals(selectedMode) && platform == null) { throw new IllegalStateException("Start Web lab with --redis-port to enable the standalone gateway"); }
        transport = "remote".equals(selectedMode)
                ? new RemoteGatewayControlTransport(platform, mapper, journal, body.get("host").asText().trim(), port, address)
                : new GatewayControlTransport(mapper, journal, body.get("host").asText().trim(), port, address);
        mode = selectedMode;
        control = new TimedOpticalControl(transport, text -> journal.add("CONTROL", text));
        return state(0);
    }

    public synchronized CompletableFuture<Void> invoke(JsonNode body) {
        requireConnected(); fields(body, "identifier", "params", "durationMillis");
        String identifier = body.path("identifier").asText();
        JsonNode service = null;
        for (JsonNode item : catalog.path("services")) {
            if (identifier.equals(item.path("identifier").asText())) { service = item; break; }
        }
        if (service == null) { throw new IllegalArgumentException("Unsupported TSL service"); }
        JsonNode params = body.path("params");
        Set<String> allowed = new HashSet<>(); service.path("inputParams").forEach(input -> allowed.add(input.path("identifier").asText()));
        fields(params, allowed.toArray(new String[0]));
        if (!params.path("channelId").isTextual() || !"1".equals(params.path("channelId").asText())) {
            throw new IllegalArgumentException("Only logical control channelId 1 is supported");
        }
        int action = integer(params, "action");
        Integer speed = params.has("speedPercent") ? integer(params, "speedPercent") : null;
        if (action == 0) {
            if (speed != null || body.has("durationMillis")) { throw new IllegalArgumentException("STOP does not take speed or duration"); }
            return control.stop(identifier);
        }
        return control.move(identifier, action, speed, integer(body, "durationMillis"));
    }

    public synchronized CompletableFuture<Void> stopAll() { requireConnected(); return control.stop(null); }

    public synchronized void disconnect() {
        requireOpen();
        if (transport == null) { return; }
        // A failed STOP keeps the session/control reservation available for an explicit retry.
        if (transport.isOnline() || control.isMotionActive()) { await(control.stop(null)); }
        control.close(); transport = null; control = null;
        journal.add("LAB", "Target disconnected");
    }

    public synchronized Map<String, Object> state(long cursor) {
        Map<String, Object> snapshot = transport == null ? new LinkedHashMap<>() : transport.snapshot();
        snapshot.put("mode", mode); snapshot.put("remoteEnabled", platform != null);
        if (platform != null) { snapshot.put("gateway", platform.info()); }
        snapshot.put("connected", transport != null); snapshot.put("motionActive", control != null && control.isMotionActive());
        snapshot.put("online", transport != null && transport.isOnline());
        if (transport == null) { snapshot.put("properties", Collections.emptyMap()); snapshot.put("propertyTimes", Collections.emptyMap()); }
        snapshot.putAll(journal.since(cursor));
        return snapshot;
    }

    public static void await(CompletableFuture<Void> future) {
        try { future.get(12, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted; command outcome must be checked", e); }
        catch (TimeoutException e) { throw new IllegalStateException("Command result timeout; check control journal", e); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) { throw (RuntimeException) e.getCause(); }
            throw new IllegalStateException("Command failed", e.getCause());
        }
    }

    private void requireOpen() { if (closed) { throw new IllegalStateException("Web lab closed"); } }
    private void requireConnected() { requireOpen(); if (control == null) { throw new IllegalStateException("Connect a target first"); } }
    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) { throw new IllegalArgumentException(field + " must be an integer"); }
        return value.intValue();
    }
    private static void fields(JsonNode body, String... names) {
        if (!body.isObject()) { throw new IllegalArgumentException("JSON object required"); }
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        body.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) { throw new IllegalArgumentException("Unsupported field: " + field); } });
    }
    @Override public synchronized void close() {
        if (closed) { return; } closed = true;
        try { if (control != null) { control.close(); transport = null; control = null; } }
        finally { if (platform != null) { platform.close(); } }
    }
}
