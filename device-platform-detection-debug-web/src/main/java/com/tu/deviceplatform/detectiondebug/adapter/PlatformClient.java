package com.tu.deviceplatform.detectiondebug.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.tofumsclient.core.OpticalControlTransport;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Fixed platform endpoint, no arbitrary URL forwarding; credentials live only in memory. */
public final class PlatformClient implements OpticalControlTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROUTES = new HashSet<>(Arrays.asList("move", "zoom", "focus", "ai-tracking/start", "ai-tracking/stop"));
    private final String base;
    private final long deviceId;
    private final String channel;
    private volatile String authorization;
    private volatile String zone;
    /** Last rejection of the currently stored token; storing a token does not prove it is still accepted. */
    private volatile boolean authenticationRejected;

    /** Platform HTTP/business 401, distinct from a missing local debug session or malformed input. */
    public static final class AuthenticationRejectedException extends IllegalStateException {
        /** Construct a safe diagnosis without claiming an expiry cause or exposing credentials. */
        public AuthenticationRejectedException() { super("平台拒绝本次认证（401），请核对或更新 Token；无法仅凭 401 判断是否过期。"); }
    }

    public PlatformClient(String base, long deviceId, String channel) {
        if (deviceId < 1 || !base.matches("https?://[^?#]+/admin-api")) { throw new IllegalArgumentException("Platform base /admin-api and device ID required"); }
        this.base = base; this.deviceId = deviceId; this.channel = channel;
    }

    /** Verify login and device before allowing control; returns only safe status/config fields. */
    public ObjectNode authenticate(String authorization, String zone) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9._~-]{8,512}")
                || (zone != null && !zone.matches("[A-Za-z0-9_,\\-]{0,128}"))) { throw new IllegalArgumentException("Valid Bearer login required"); }
        JsonNode device = call("GET", "/c-iot/iot/device/get?id=" + deviceId, null, authorization, zone).path("data");
        if (!"tofu-ms-tjson".equals(device.path("protocolType").asText())) { throw new IllegalArgumentException("Configured device must use tofu-ms-tjson"); }
        this.authorization = authorization; this.zone = zone; authenticationRejected = false;
        ObjectNode result = JSON.createObjectNode(); result.put("deviceId", deviceId); result.put("state", device.path("state").asInt());
        JsonNode config = device.path("config");
        try { if (config.isTextual()) { config = JSON.readTree(config.asText()); } }
        catch (IOException e) { throw new IllegalStateException("Device configuration is invalid"); }
        for (String name : new String[]{"longitude", "latitude", "altitude", "trackingNorthOffset", "trackingEnabled", "guidanceMode"}) {
            if (config != null && config.has(name)) { result.set(name, config.get(name)); }
        }
        return result;
    }

    /** Allowlisted existing business routes; true is API acceptance, never proof of a physical action. */
    public void command(String route, ObjectNode params) {
        if (!ROUTES.contains(route)) { throw new IllegalArgumentException("Unsupported business route"); }
        params.put("deviceId", deviceId);
        JsonNode result = call("POST", "/b-detect/ptz/" + route, params, authorization, zone);
        if (!result.path("data").asBoolean(false)) { throw new IllegalStateException("Platform did not accept command; inspect task ownership/configuration"); }
    }

    /** Query the exact camera/task pair's last event; never treat its age as real-time readiness. */
    public JsonNode aiStatus(String trackingId) {
        if (trackingId == null || !trackingId.matches("manual-[A-Za-z0-9-]{1,57}")) {
            throw new IllegalArgumentException("Invalid manual AI task");
        }
        JsonNode status = call("GET", "/b-detect/ptz/ai-tracking/status?deviceId=" + deviceId
                + "&trackingId=" + trackingId, null, authorization, zone).path("data");
        if (!status.isObject() || status.path("deviceId").asLong(-1) != deviceId
                || !trackingId.equals(status.path("trackingId").asText())) {
            throw new IllegalStateException("Tracking feedback task mismatch or status API unavailable");
        }
        String phase = status.path("state").asText();
        if (!Arrays.asList("UNKNOWN", "GUIDING", "LOCKED", "LOST", "FAILED", "STOPPED").contains(phase)) {
            throw new IllegalStateException("Invalid tracking feedback state");
        }
        if (!"UNKNOWN".equals(phase)) {
            long age = status.path("feedbackAgeMillis").asLong(-1);
            long at = status.path("feedbackTime").asLong(-1), server = status.path("serverTime").asLong(-1);
            if (age < 0 || at < 1 || server < 1 || at > server + 1000) {
                throw new IllegalStateException("Invalid tracking feedback timestamp");
            }
        }
        return status;
    }

    /** Fixed read-only business query; no Nacos management or generic URL forwarding. */
    public JsonNode preflight(String sourceSerial) {
        if (sourceSerial == null || !sourceSerial.matches("[A-Za-z0-9_-]{1,64}")) { throw new IllegalArgumentException("Invalid source serial"); }
        JsonNode out = call("GET", "/b-detect/ptz/preflight?deviceId=" + deviceId + "&sourceSerial=" + sourceSerial,
                null, authorization, zone).path("data");
        if (!out.isObject() || out.path("deviceId").asLong(-1) != deviceId || !out.path("checks").isArray()
                || out.path("serverTime").asLong(0) < 1) { throw new IllegalStateException("Preflight API unavailable or invalid response"); }
        return out;
    }

    /** Fresh device document stays internal: never return credentials or the complete config to the browser. */
    public ObjectNode deviceDocument() {
        JsonNode device = call("GET", "/c-iot/iot/device/get?id=" + deviceId, null, authorization, zone).path("data");
        if (!device.isObject() || !"tofu-ms-tjson".equals(device.path("protocolType").asText())) {
            throw new IllegalStateException("Configured device no longer uses tofu-ms-tjson");
        }
        return (ObjectNode) device;
    }

    /** Parse strictly; malformed or missing configs are not replaced by an empty object. */
    public ObjectNode configuration(JsonNode document) {
        JsonNode value = document.path("config");
        try { if (value.isTextual()) { value = JSON.readTree(value.asText()); } }
        catch (IOException e) { throw new IllegalStateException("Device configuration is invalid"); }
        if (value == null || !value.isObject()) { throw new IllegalStateException("Device configuration must be an object"); }
        return (ObjectNode) value.deepCopy();
    }

    /** Existing device update API, bounded to this camera; preserves required serial, never forwards credentials. */
    public void updateConfiguration(ObjectNode document, ObjectNode configuration) {
        String serial = document.path("serialNumber").asText("");
        if (serial.isEmpty()) { throw new IllegalStateException("Device serial required for update"); }
        ObjectNode body = JSON.createObjectNode(); body.put("id", deviceId); body.put("serialNumber", serial);
        body.put("config", configuration.toString());
        JsonNode response = call("PUT", "/c-iot/iot/device/update", body, authorization, zone);
        if (!response.path("data").asBoolean(false)) { throw new IllegalStateException("Device config update not confirmed; recovery retained"); }
    }

    private JsonNode call(String method, String path, JsonNode body, String auth, String zoneId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(2000); connection.setReadTimeout(5000);
            connection.setRequestMethod(method); connection.setRequestProperty("Authorization", auth == null ? "" : auth);
            if (zoneId != null && !zoneId.isEmpty()) { connection.setRequestProperty("X-Defence-Zone-Ids", zoneId); }
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(JSON.writeValueAsBytes(body)); }
            }
            int status = connection.getResponseCode();
            if (status == 401) { throw authenticationRejected(auth); }
            if (status != 200) { throw new IllegalStateException("Platform HTTP " + status + "; command outcome may be unknown"); }
            JsonNode response;
            try (InputStream in = connection.getInputStream()) { response = JSON.readTree(readLimited(in)); }
            if (response == null || !response.path("code").isIntegralNumber() || response.path("code").asInt() != 0) {
                int code = response == null ? -1 : response.path("code").asInt(-1);
                if (code == 401) { throw authenticationRejected(auth); }
                throw new IllegalStateException("Platform rejected request, code=" + code);
            }
            if (auth != null && auth.equals(authorization)) { authenticationRejected = false; }
            return response;
        } catch (IOException e) { throw new IllegalStateException("Platform connection failed; command outcome may be unknown"); }
        finally { if (connection != null) { connection.disconnect(); } }
    }

    /** @param attempted token used by the rejected request @return safe error; a failed replacement preserves the current token */
    private AuthenticationRejectedException authenticationRejected(String attempted) {
        if (attempted != null && attempted.equals(authorization)) { authenticationRejected = true; }
        return new AuthenticationRejectedException();
    }

    /** @return NOT_CONNECTED, STORED or REJECTED; never returns the token or promises future validity */
    public String authenticationState() { return authorization == null ? "NOT_CONNECTED" : authenticationRejected ? "REJECTED" : "STORED"; }

    /** @return currently authenticated defence zone; editing the page alone cannot change it */
    public String authenticationZone() { return zone; }

    static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > 262144) { throw new IllegalArgumentException("Body exceeds limit"); }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Override public boolean isOnline() { return authorization != null; }
    @Override public long getConnectionGeneration() { return 1; }
    @Override public void send(String identifier, int action, Integer speed) {
        String route = "ptz_control".equals(identifier) ? "move" : "zoom_control".equals(identifier) ? "zoom" : "focus_control".equals(identifier) ? "focus" : null;
        if (route == null) { throw new IllegalArgumentException("Unsupported actuator"); }
        ObjectNode request = JSON.createObjectNode(); request.put("deviceChannelCode", channel);
        request.put("move".equals(route) ? "direction" : "action", action);
        if (speed != null) { request.put("speed", speed); }
        command(route, request);
    }
    @Override public void close() { authorization = null; zone = null; authenticationRejected = false; }
}
