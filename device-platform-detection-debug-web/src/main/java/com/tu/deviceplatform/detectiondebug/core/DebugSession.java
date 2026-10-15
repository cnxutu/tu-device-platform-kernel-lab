package com.tu.deviceplatform.detectiondebug.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.detectiondebug.adapter.PlatformClient;
import com.tu.deviceplatform.tofumsclient.core.TimedOpticalControl;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** One shared camera reservation and bounded point sender; uses only real platform/protocol entry points. */
public final class DebugSession implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final PlatformClient platform;
    private final TimedOpticalControl motion;
    private final String deviceNo;
    private final BiConsumer<String, JsonNode> publisher;
    private final Deque<ObjectNode> journal = new ArrayDeque<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "detection-debug-points"); thread.setDaemon(true); return thread;
    });
    private ScheduledFuture<?> sending;
    private List<ObjectNode> points = Collections.emptyList();
    private int pointIndex;
    private long expiresAt, heartbeatAt, sentCount;
    private String targetId, aiTaskId;
    private String lastAiTaskId;
    private ObjectNode deviceStatus;
    private final SceneConfiguration scenes;

    public DebugSession(PlatformClient platform, String deviceNo, BiConsumer<String, JsonNode> publisher) {
        NtXr1Payload.identifier(deviceNo);
        this.platform = platform; this.deviceNo = deviceNo; this.publisher = publisher;
        scenes = new SceneConfiguration(platform, deviceNo);
        motion = new TimedOpticalControl(platform, message -> log("CONTROL", message, null));
    }

    /** Login/renewal validates the configured device and never exposes device secrets. */
    public synchronized ObjectNode authenticate(String authorization, String zone) {
        deviceStatus = platform.authenticate(authorization, zone); return deviceStatus.deepCopy();
    }

    /** Explicit controls and bounded scene configuration; calibration/global switches remain unchanged. */
    public synchronized ObjectNode action(String operation, JsonNode body) {
        switch (operation) {
            case "scene-select":
                idleForPoints(); scenes.select(text(body, "scene")); break;
            case "scene-check": scenes.check(); break;
            case "scene-preview": idleForPoints(); scenes.preview(body); break;
            case "scene-apply":
                idleForPoints(); scenes.apply();
                log("CONFIG", "场景配置已保存回读；网关应用及设备动作仍需另验，结束后请恢复", null); break;
            case "scene-finish":
                stopPoints();
                if (aiTaskId != null) { stopAi(); }
                if (motion.isMotionActive()) { await(motion.stop(null)); }
                scenes.restore();
                log("CONFIG", "本工具活动已停止，临时字段已恢复回读；未停止其他平台任务", null); break;
            case "move":
                idleForControl();
                await(motion.move(text(body, "identifier"), integer(body, "action", 1, 8),
                        "focus_control".equals(body.path("identifier").asText()) ? null : integer(body, "speed", 1, 100),
                        integer(body, "durationMillis", 100, 10000)));
                break;
            case "motion-stop": await(motion.stop(null)); break;
            case "ai-start":
                idleForControl();
                if (motion.isMotionActive()) { throw new IllegalStateException("Stop the active actuator first"); }
                ObjectNode ai = json.createObjectNode();
                String requestedId = "manual-" + UUID.randomUUID(); ai.put("trackingId", requestedId);
                int capture = integer(body, "captureTimeoutSeconds", 5, 120);
                int session = integer(body, "sessionTimeoutSeconds", 5, 600);
                if (session < capture) { throw new IllegalArgumentException("Session must cover capture timeout"); }
                ai.put("captureTimeoutSeconds", capture); ai.put("sessionTimeoutSeconds", session);
                // Reserve before sending: a timeout can still mean accepted; retain ID so stop remains possible.
                aiTaskId = requestedId; lastAiTaskId = requestedId;
                platform.command("ai-tracking/start", ai);
                log("AI", "启动请求已受理；不代表目标已锁定", ai); break;
            case "ai-stop": stopAi(); break;
            case "preview": return preview(body);
            case "point-once":
                idleForPoints();
                ObjectNode one = NtXr1Payload.point(body.path("point"));
                String oneTarget = text(body, "targetId"); NtXr1Payload.identifier(oneTarget);
                scenes.requirePointScene(); send(oneTarget, one);
                break;
            case "points-start":
                idleForPoints();
                String requestedTarget = text(body, "targetId"); NtXr1Payload.identifier(requestedTarget);
                JsonNode inputPoints = body.path("points");
                if (!inputPoints.isArray() || inputPoints.size() < 1 || inputPoints.size() > 30) {
                    throw new IllegalArgumentException("Provide 1..30 points");
                }
                List<ObjectNode> validated = new ArrayList<>();
                inputPoints.forEach(p -> validated.add(NtXr1Payload.point(p)));
                int interval = integer(body, "intervalMillis", 1000, 60000);
                int duration = integer(body, "durationSeconds", 1, 600);
                scenes.requirePointScene();
                targetId = requestedTarget; points = validated; pointIndex = 0; sentCount = 0;
                expiresAt = System.currentTimeMillis() + duration * 1000L;
                sending = worker.scheduleWithFixedDelay(this::tick, 0, interval, TimeUnit.MILLISECONDS);
                log("SOURCE", "连续上报已安排；走实际MQTT接入，未自动开启平台联动", null); break;
            case "point-update":
                if (!isSending()) { throw new IllegalStateException("Start continuous sending first"); }
                points = Collections.singletonList(NtXr1Payload.point(body.path("point"))); pointIndex = 0;
                log("SOURCE", "已更新当前坐标；目标标识与任务截止时间保持不变", points.get(0)); break;
            case "points-stop": stopPoints(); break;
            default: throw new IllegalArgumentException("Unknown operation");
        }
        return state();
    }

    /** Preview is non-actuating and uses the same encoder as MQTT sending. */
    public ObjectNode preview(JsonNode body) {
        ObjectNode out = json.createObjectNode(); out.put("topic", NtXr1Payload.topic(deviceNo, false));
        out.set("payload", NtXr1Payload.locate(deviceNo, text(body, "targetId"), body.path("point"), System.currentTimeMillis()));
        return out;
    }

    private synchronized void tick() {
        if (!isSending()) { return; }
        if (System.currentTimeMillis() >= expiresAt) { stopPoints(); return; }
        try {
            send(targetId, points.get(pointIndex)); sentCount++;
            if (pointIndex < points.size() - 1) { pointIndex++; }
        } catch (RuntimeException e) {
            stopPoints(); log("ERROR", e.getMessage() + "; 已停止后续上报，无自动重试", null);
        }
    }

    private void send(String target, ObjectNode point) {
        // Validate both frames before publishing the heartbeat.
        long now = System.currentTimeMillis(); ObjectNode payload = NtXr1Payload.locate(deviceNo, target, point, now);
        if (now - heartbeatAt >= 5000) {
            publisher.accept(NtXr1Payload.topic(deviceNo, true), NtXr1Payload.heartbeat(deviceNo, now)); heartbeatAt = now;
        }
        publisher.accept(NtXr1Payload.topic(deviceNo, false), payload);
        log("POINT", "报文已写入MQTT；不代表业务已消费或摄像头到位", payload);
    }

    private void stopAi() {
        if (aiTaskId == null) { throw new IllegalStateException("No AI task owned by this debug service"); }
        ObjectNode stop = json.createObjectNode(); stop.put("trackingId", aiTaskId);
        platform.command("ai-tracking/stop", stop);
        log("AI", "AI关闭/停止请求成功；未请求回位", stop); aiTaskId = null;
    }

    private void idleForControl() {
        if (aiTaskId != null || isSending()) { throw new IllegalStateException("Stop this tool's AI/point sender before manual control"); }
    }
    private void idleForPoints() {
        idleForControl();
        if (motion.isMotionActive()) { throw new IllegalStateException("Stop the active actuator first"); }
    }
    private boolean isSending() { return sending != null && !sending.isDone(); }
    private void stopPoints() {
        if (sending != null) { sending.cancel(false); sending = null; }
        log("SOURCE", "已停止本工具上报；既有业务任务仍按平台超时/停止策略退出", null);
    }

    public synchronized ObjectNode state() {
        ObjectNode out = json.createObjectNode(); out.put("simulationDeviceNo", deviceNo);
        out.put("platformAuthentication", platform.authenticationState());
        if (platform.authenticationZone() != null) { out.put("zone", platform.authenticationZone()); }
        out.put("motionActive", motion.isMotionActive()); out.put("sourceRunning", isSending());
        out.put("sentCount", sentCount); out.put("pointIndex", pointIndex); out.put("sourceExpiresAt", expiresAt);
        if (targetId != null) { out.put("targetId", targetId); }
        if (aiTaskId != null) { out.put("aiTaskId", aiTaskId); }
        if (lastAiTaskId != null) { out.put("lastAiTaskId", lastAiTaskId); }
        out.put("aiFeedbackAvailable", true);
        out.set("scene", scenes.state());
        if (deviceStatus != null) { out.set("device", deviceStatus.deepCopy()); }
        synchronized (journal) { out.set("journal", json.valueToTree(new ArrayList<>(journal))); }
        return out;
    }

    /** Read-only query outside the session monitor so a slow status response does not block STOP. */
    public JsonNode aiStatus() {
        final String task;
        synchronized (this) { task = lastAiTaskId; }
        if (task == null) { throw new IllegalStateException("No manual AI task to query"); }
        return platform.aiStatus(task);
    }
    private void log(String type, String message, JsonNode value) {
        ObjectNode entry = json.createObjectNode(); entry.put("time", System.currentTimeMillis());
        entry.put("type", type); entry.put("message", message);
        if (value != null) { entry.set("data", value.deepCopy()); }
        synchronized (journal) { journal.addLast(entry); while (journal.size() > 100) { journal.removeFirst(); } }
    }
    private static void await(CompletableFuture<Void> future) {
        try { future.get(15, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted; check command outcome"); }
        catch (TimeoutException e) { throw new IllegalStateException("Command response timed out; automatic stop may still be pending"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof PlatformClient.AuthenticationRejectedException) { throw (PlatformClient.AuthenticationRejectedException) e.getCause(); }
            throw new IllegalStateException(e.getCause().getMessage());
        }
    }
    public static int integer(JsonNode body, String key, int min, int max) {
        JsonNode value = body.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw new IllegalArgumentException(key + " must be integer " + min + ".." + max);
        }
        return value.intValue();
    }
    public static String text(JsonNode body, String key) {
        if (!body.path(key).isTextual() || body.path(key).asText().trim().isEmpty()) { throw new IllegalArgumentException(key + " required"); }
        return body.path(key).asText().trim();
    }
    @Override public synchronized void close() {
        stopPoints(); worker.shutdownNow();
        try { if (aiTaskId != null) { stopAi(); } }
        finally { motion.close(); }
    }
}
