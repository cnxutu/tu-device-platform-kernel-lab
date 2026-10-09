package com.tu.deviceplatform.tofumsweb.adapter;

import cn.hutool.extra.spring.SpringUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofumsclient.core.OpticalControlTransport;
import com.tu.deviceplatform.tofumsweb.core.LabJournal;
import com.xmkj.codec.adapter.uav.device.tofums.TofuMsCustomProtocolAdapter;
import com.xmkj.codec.adapter.uav.device.tofums.command.TofuMsCommandRegistry;
import com.xmkj.codec.adapter.uav.device.tofums.mapper.TofuMsUpstreamMapper;
import com.xmkj.common.iot.gateway.adapter.outbound.device.*;
import com.xmkj.common.iot.gateway.autoconfigure.IotGatewayProperties;
import com.xmkj.common.iot.gateway.core.cache.DeviceInvokeResultStore;
import com.xmkj.common.iot.gateway.core.handler.downstream.DownstreamSubscriberManager;
import com.xmkj.common.iot.gateway.core.handler.upstream.DeviceReplyResultHandler;
import com.xmkj.common.iot.gateway.domain.ServerInfo;
import com.xmkj.common.iot.gateway.protocol.custom.IoTCustomGatewayServer;
import com.xmkj.sdk.iot.common.biz.dto.*;
import com.xmkj.sdk.iot.common.enums.IotDeviceMessageMethodEnum;
import com.xmkj.sdk.iot.common.enums.IotDeviceStateEnum;
import com.xmkj.sdk.iot.common.messagebus.core.*;
import com.xmkj.sdk.iot.common.mq.message.IotDeviceMessage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

/** Real Custom gateway/adapter route with explicit, in-memory platform boundaries. */
public final class GatewayControlTransport implements LabControlTransport {
    private static final long DEVICE_ID = 7001L;
    private static final String PRODUCT_KEY = "lab-optical", DEVICE_NAME = "lab-gateway-camera";
    private final ObjectMapper mapper;
    private final LabJournal journal;
    private final TofuMsCustomProtocolAdapter adapter;
    private final IoTCustomGatewayServer gateway;
    private final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
    private final LocalResultStore results = new LocalResultStore();
    private final LocalMessageBus bus = new LocalMessageBus();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, JsonNode> properties = new LinkedHashMap<>();
    private final Map<String, Long> propertyTimes = new LinkedHashMap<>();
    private final Map<String, Object> target;
    private final Map<String, Object> context;
    private final String sessionId = UUID.randomUUID().toString();
    private volatile boolean online;
    private volatile long lastPropertyTime;
    private volatile JsonNode syncResult;
    private volatile JsonNode lastAck;
    private volatile long lastAckTime;

    public GatewayControlTransport(ObjectMapper mapper, LabJournal journal, String host, int port, int address) {
        this.mapper = mapper; this.journal = journal;
        target = new LinkedHashMap<>(); target.put("host", host); target.put("port", port); target.put("ptzAddress", address);
        context = new LinkedHashMap<>(target); context.put("sessionId", sessionId);
        adapter = new TofuMsCustomProtocolAdapter(mapper, new TofuMsCommandRegistry(mapper), new TofuMsUpstreamMapper(mapper));
        adapter.setTrafficObserver((identity, outbound, frame) -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("hex", hex(frame));
            if (frame[2] == 1 || frame[2] == 3) {
                record.put("json", new String(frame, 7, frame.length - 7, StandardCharsets.UTF_8));
            }
            journal.add(outbound ? "TX_FRAME" : "RX_FRAME", record, context);
        });
        IotGatewayProperties.CustomProperties config = new IotGatewayProperties.CustomProperties();
        config.setProtocolCodecId(TofuMsCustomProtocolAdapter.CODEC_ID); config.setPort(18090); config.setTags("lab-tofu-ms");
        config.setAdapterProperties(Collections.singletonMap("commandIntervalMillis", "150"));
        gateway = new IoTCustomGatewayServer(TofuMsCustomProtocolAdapter.BEAN_NAME, config, adapter,
                new LocalForward(), new DeviceReplyResultHandler(results), results, bus, new LocalDevices(), new DownstreamSubscriberManager());
        try {
            // Explicit beans only: no Boot auto-configuration, discovery, RPC, Redis or MQ clients.
            spring.registerBean(DeviceInvokeResultStore.class, () -> results);
            spring.register(SpringUtil.class); spring.refresh();
            gateway.refreshAndGetServerInfo().setIotGatewayId(8001L); gateway.init();
            IotDeviceConnectionDTO device = new IotDeviceConnectionDTO().setDeviceId(DEVICE_ID)
                    .setProductKey(PRODUCT_KEY).setDeviceName(DEVICE_NAME).setProtocolType("CUSTOM").setIp(host)
                    .setConfig(mapper.writeValueAsString(target));
            syncResult = mapper.valueToTree(exchange(new IotDeviceMessage()
                    .setMethod(IotDeviceMessageMethodEnum.GATEWAY_DEVICE_CONFIG_SYNC.getMethod())
                    .setData(Collections.singletonList(device))).getData());
        } catch (Exception e) {
            close(); throw new IllegalStateException("Gateway configuration sync failed", e);
        }
    }

    @Override public boolean isOnline() { return online && !closed.get(); }
    @Override public long getConnectionGeneration() { return generation.get(); }

    @Override public void send(String identifier, int action, Integer speed) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("channelId", "1"); parameters.put("action", action);
        if (speed != null) { parameters.put("speedPercent", speed); }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("identifier", identifier); envelope.put("params", parameters);
        IotDeviceMessage reply = exchange(new IotDeviceMessage().setDeviceId(DEVICE_ID)
                .setMethod("thing.service.invoke").setParams(envelope));
        if (!"SENT".equals(mapper.valueToTree(reply.getData()).path("deliveryState").asText())) {
            throw new IllegalStateException("Gateway did not confirm TCP write");
        }
    }

    private IotDeviceMessage exchange(IotDeviceMessage message) {
        String requestId = UUID.randomUUID().toString(), key = results.buildResultKey(requestId);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("requestId", requestId); inner.put("resultKey", key); inner.put("resultExpireSeconds", 30L);
        message.setRequestId(requestId).setInnerIotParams(inner);
        results.expect(key);
        try {
            journal.add("DOWNSTREAM", message, context);
            // Dispatch through the registered gateway subscriber, including its result handling.
            bus.post(gateway.getTopic(), message);
            IotDeviceMessage reply = results.take(key);
            if (reply == null) { throw new IllegalStateException("Gateway synchronous result missing"); }
            journal.add("RESULT", reply, context);
            if (!Integer.valueOf(0).equals(reply.getCode())) { throw new IllegalStateException(reply.getMsg()); }
            return reply;
        } finally { results.forget(key); }
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("target", target); state.put("sessionId", sessionId); state.put("online", isOnline()); state.put("generation", generation.get());
        state.put("properties", new LinkedHashMap<>(properties)); state.put("propertyTimes", new LinkedHashMap<>(propertyTimes));
        state.put("lastPropertyTime", lastPropertyTime); state.put("syncResult", syncResult);
        state.put("lastAck", lastAck); state.put("lastAckTime", lastAckTime);
        return state;
    }

    private synchronized void receive(IotDeviceMessage message) {
        if (IotDeviceMessageMethodEnum.STATE_UPDATE.getMethod().equals(message.getMethod())) {
            boolean next = String.valueOf(IotDeviceStateEnum.ONLINE.getState()).equals(mapper.valueToTree(message.getParams()).path("state").asText());
            if (next != online) { online = next; generation.incrementAndGet(); }
            journal.add("CONNECTION", message, context);
        } else if ("thing.property.post".equals(message.getMethod())) {
            lastPropertyTime = System.currentTimeMillis();
            mapper.valueToTree(message.getParams()).fields().forEachRemaining(field -> {
                properties.put(field.getKey(), field.getValue()); propertyTimes.put(field.getKey(), lastPropertyTime);
            });
            journal.add("PROPERTY", message, context);
        } else if ("thing.event.post".equals(message.getMethod())) {
            // Preserve the absent requestId: protocol ACK has no service correlation.
            lastAck = mapper.valueToTree(message); lastAckTime = System.currentTimeMillis();
            journal.add("ACK", message, context);
        } else { journal.add("UPSTREAM", message, context); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        try { gateway.destroy(); }
        finally { online = false; generation.incrementAndGet(); spring.close(); }
    }

    private final class LocalForward extends IotDeviceMessageForwardAdapter {
        @Override public void sendDeviceMessage(IotDeviceMessage message, String productKey, String deviceName, ServerInfo info) {
            if (!PRODUCT_KEY.equals(productKey) || !DEVICE_NAME.equals(deviceName)) { throw new IllegalArgumentException("Unknown lab device"); }
            message.setDeviceId(DEVICE_ID).setServerId(info.getServerId()).setIotGatewayId(info.getIotGatewayId()).setTags(info.getTags());
            receive(message);
        }
    }

    private static final class LocalDevices extends IotDeviceServiceAdapter {
        @Override public IotDeviceRespDTO getDeviceFromCache(Long id) {
            if (!Long.valueOf(DEVICE_ID).equals(id)) { throw new IllegalArgumentException("Unknown lab device"); }
            return new IotDeviceRespDTO().setId(DEVICE_ID).setProductKey(PRODUCT_KEY).setDeviceName(DEVICE_NAME);
        }
        @Override public IotDeviceRespDTO getDeviceFromCache(String productKey, String deviceName) {
            if (!PRODUCT_KEY.equals(productKey) || !DEVICE_NAME.equals(deviceName)) { throw new IllegalArgumentException("Unknown lab identity"); }
            return getDeviceFromCache(DEVICE_ID);
        }
    }

    private static final class LocalResultStore extends DeviceInvokeResultStore {
        private final Map<String, Optional<IotDeviceMessage>> inflight = new ConcurrentHashMap<>();
        LocalResultStore() { super(null); }
        void expect(String key) { inflight.put(key, Optional.empty()); }
        IotDeviceMessage take(String key) { Optional<IotDeviceMessage> value = inflight.remove(key); return value == null ? null : value.orElse(null); }
        void forget(String key) { inflight.remove(key); }
        @Override public void pushResult(String key, IotDeviceMessage message, long expireSeconds) {
            if (!inflight.containsKey(key)) { throw new IllegalStateException("Unexpected gateway result"); }
            inflight.put(key, Optional.of(message));
        }
        @Override public void savePendingResultKey(String id, String key, long expires) { throw new UnsupportedOperationException("Lab supports SYNC_RESULT only"); }
        @Override public String getAndRemovePendingResultKey(String id) { return null; }
        @Override public void removePendingResultKey(String id) { }
    }

    private static final class LocalMessageBus implements IotMessageBus {
        private IotMessageSubscriber<Object> subscriber;
        @SuppressWarnings("unchecked") @Override public void register(IotMessageSubscriber<?> subscriber) {
            if (this.subscriber != null) { throw new IllegalStateException("Only one lab gateway supported"); }
            this.subscriber = (IotMessageSubscriber<Object>) subscriber;
        }
        @Override public void post(String topic, Object message) {
            if (subscriber == null || !subscriber.getTopic().equals(topic)) { throw new IllegalArgumentException("Unknown lab topic"); }
            subscriber.onMessage(message);
        }
    }

    private static String hex(byte[] frame) {
        StringBuilder value = new StringBuilder(frame.length * 2);
        for (byte item : frame) { value.append(String.format("%02X", item & 255)); }
        return value.toString();
    }
}
