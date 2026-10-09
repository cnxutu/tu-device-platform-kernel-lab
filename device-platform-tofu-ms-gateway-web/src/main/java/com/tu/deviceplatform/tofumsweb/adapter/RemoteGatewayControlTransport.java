package com.tu.deviceplatform.tofumsweb.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofumsweb.core.LabJournal;
import com.xmkj.sdk.enums.optical.TofuMsProtocol;
import com.xmkj.sdk.iot.common.biz.dto.IotDeviceConnectionDTO;
import com.xmkj.sdk.iot.common.enums.*;
import com.xmkj.sdk.iot.common.mq.message.IotDeviceMessage;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Sends platform messages to a separate gateway process. Never opens a device socket. */
public final class RemoteGatewayControlTransport implements LabControlTransport {
    private final RemoteGatewayPlatform platform;
    private final ObjectMapper mapper;
    private final LabJournal journal;
    private final Map<String, Object> target = new LinkedHashMap<>();
    private final Map<String, JsonNode> properties = new LinkedHashMap<>();
    private final Map<String, Long> propertyTimes = new LinkedHashMap<>();
    private final String sessionId = UUID.randomUUID().toString();
    private volatile long gatewayGeneration;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile boolean online, closed;
    private volatile long lastPropertyTime, lastAckTime;
    private volatile JsonNode lastAck;
    private JsonNode syncResult;

    public RemoteGatewayControlTransport(RemoteGatewayPlatform platform, ObjectMapper mapper, LabJournal journal, String host, int port, int address) {
        this.platform = platform; this.mapper = mapper; this.journal = journal; gatewayGeneration = platform.generation();
        target.put("host", host); target.put("port", port); target.put("ptzAddress", address);
        platform.attach(this);
        try {
            syncTarget();
        } catch (Exception e) { closed = true; platform.detach(this); throw new IllegalStateException("Remote gateway configuration sync failed", e); }
    }
    public boolean isOnline() { return !closed && online && platform.isReady() && gatewayGeneration == platform.generation(); }
    public long getConnectionGeneration() { return connectionGeneration.get() + platform.generation(); }
    public void send(String identifier, int action, Integer speed) {
        if (action == 0 && platform.isReady() && gatewayGeneration != platform.generation()) {
            // Only an explicit STOP may restore a target after gateway restart; movement is never replayed.
            gatewayGeneration = platform.generation(); online = false;
            syncTarget();
            long deadline = System.currentTimeMillis() + 5000;
            while (!isOnline() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("STOP interrupted", e); }
            }
        }
        Map<String, Object> params = new LinkedHashMap<>(); params.put("channelId", "1"); params.put("action", action);
        if (speed != null) { params.put("speedPercent", speed); }
        Map<String, Object> envelope = new LinkedHashMap<>(); envelope.put("identifier", identifier); envelope.put("params", params);
        IotDeviceMessage reply = exchange(new IotDeviceMessage().setDeviceId(RemoteGatewayPlatform.DEVICE_ID)
                .setMethod("thing.service.invoke").setParams(envelope));
        if (!"SENT".equals(mapper.valueToTree(reply.getData()).path("deliveryState").asText())) { throw new IllegalStateException("Gateway did not confirm TCP write"); }
    }
    private void syncTarget() {
        IotDeviceConnectionDTO device = new IotDeviceConnectionDTO().setDeviceId(RemoteGatewayPlatform.DEVICE_ID)
                .setProductKey(TofuMsProtocol.PRODUCT_KEY).setDeviceName(RemoteGatewayPlatform.DEVICE_NAME)
                .setProtocolType("CUSTOM").setIp(String.valueOf(target.get("host")));
        try { device.setConfig(mapper.writeValueAsString(target)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        syncResult = mapper.valueToTree(exchange(new IotDeviceMessage().setMethod(IotDeviceMessageMethodEnum.GATEWAY_DEVICE_CONFIG_SYNC.getMethod())
                .setData(Collections.singletonList(device))).getData());
    }
    private IotDeviceMessage exchange(IotDeviceMessage message) {
        if (closed || !platform.isReady() || platform.generation() != gatewayGeneration) { throw new IllegalStateException("Standalone gateway unavailable or restarted; reconnect explicitly"); }
        String requestId = UUID.randomUUID().toString(), resultKey = "iot:gateway:invoke:result:" + requestId;
        Map<String, Object> inner = new LinkedHashMap<>(); inner.put("requestId", requestId); inner.put("resultKey", resultKey);
        inner.put("resultExpireSeconds", 30L); inner.put("expiresAtMillis", System.currentTimeMillis() + 5000);
        message.setRequestId(requestId).setInnerIotParams(inner);
        message.setServerId(platform.serverId()).setIotGatewayId(RemoteGatewayPlatform.GATEWAY_ID);
        journal.add("DOWNSTREAM", message, context());
        try {
            platform.send(message);
            String json = platform.redis().opsForList().rightPop(resultKey, 6, TimeUnit.SECONDS);
            if (json == null) { throw new IllegalStateException("Gateway result timeout; command outcome unknown; movement is not retried"); }
            IotDeviceMessage reply = mapper.readValue(json, IotDeviceMessage.class); journal.add("RESULT", reply, context());
            if (!requestId.equals(reply.getRequestId()) || !Integer.valueOf(0).equals(reply.getCode())) { throw new IllegalStateException(reply.getMsg()); }
            return reply;
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid gateway result", e); }
        finally { platform.redis().delete(resultKey); }
    }
    synchronized void receive(IotDeviceMessage message) {
        if (closed || !Long.valueOf(RemoteGatewayPlatform.DEVICE_ID).equals(message.getDeviceId())) { return; }
        if ("thing.state.update".equals(message.getMethod()) || IotDeviceMessageMethodEnum.STATE_UPDATE.getMethod().equals(message.getMethod())) {
            boolean next = String.valueOf(IotDeviceStateEnum.ONLINE.getState()).equals(mapper.valueToTree(message.getParams()).path("state").asText());
            if (next != online) { online = next; connectionGeneration.incrementAndGet(); }
            journal.add("CONNECTION", message, context());
        } else if ("thing.property.post".equals(message.getMethod())) {
            lastPropertyTime = System.currentTimeMillis();
            mapper.valueToTree(message.getParams()).fields().forEachRemaining(field -> { properties.put(field.getKey(), field.getValue()); propertyTimes.put(field.getKey(), lastPropertyTime); });
            journal.add("PROPERTY", message, context());
        } else if ("thing.event.post".equals(message.getMethod())) {
            lastAck = mapper.valueToTree(message); lastAckTime = System.currentTimeMillis(); journal.add("ACK", message, context());
        } else if ("lab.tcp.frame".equals(message.getMethod())) {
            JsonNode record = mapper.valueToTree(message.getParams());
            journal.add(record.path("outbound").asBoolean() ? "TX_FRAME" : "RX_FRAME", record, context());
        }
    }
    private Map<String, Object> context() { Map<String, Object> value = new LinkedHashMap<>(target); value.put("sessionId", sessionId); value.put("mode", "remote"); value.put("gateway", platform.info()); return value; }
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> value = context(); value.put("target", new LinkedHashMap<>(target)); value.put("online", isOnline());
        value.put("properties", new LinkedHashMap<>(properties)); value.put("propertyTimes", new LinkedHashMap<>(propertyTimes));
        value.put("lastPropertyTime", lastPropertyTime); value.put("lastAck", lastAck); value.put("lastAckTime", lastAckTime);
        value.put("syncResult", syncResult); return value;
    }
    public void close() {
        if (closed) { return; }
        if (platform.isReady() && gatewayGeneration != platform.generation()) { gatewayGeneration = platform.generation(); }
        // Do not release the target on failure: caller can retry STOP/disconnect.
        exchange(new IotDeviceMessage().setMethod(IotDeviceMessageMethodEnum.GATEWAY_DEVICE_CONFIG_SYNC.getMethod()).setData(Collections.emptyList()));
        closed = true; online = false; connectionGeneration.incrementAndGet(); platform.detach(this);
    }
}
