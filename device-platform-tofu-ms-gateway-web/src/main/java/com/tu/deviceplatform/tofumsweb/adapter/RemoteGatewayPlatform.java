package com.tu.deviceplatform.tofumsweb.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmkj.sdk.enums.optical.TofuMsProtocol;
import com.xmkj.sdk.iot.common.biz.dto.IotDeviceRespDTO;
import com.xmkj.sdk.iot.common.constants.MQConstants;
import com.xmkj.sdk.iot.common.messagebus.core.IotMessageSubscriber;
import com.xmkj.sdk.iot.common.messagebus.core.redis.IotRedisMessageBus;
import com.xmkj.sdk.iot.common.mq.message.*;
import com.xmkj.sdk.iot.common.util.IotDeviceMessageUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;

/** Simulated platform side of the existing gateway RPC, message bus and result contracts. */
public final class RemoteGatewayPlatform implements AutoCloseable {
    public static final long DEVICE_ID = 7001L, GATEWAY_ID = 8001L;
    public static final String DEVICE_NAME = "lab-gateway-camera";
    private final ObjectMapper mapper;
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;
    private final IotRedisMessageBus bus;
    private volatile RemoteGatewayControlTransport current;
    private volatile String serverId;
    private volatile long heartbeatTime;
    private volatile long generation;
    private boolean subscribed;

    public RemoteGatewayPlatform(ObjectMapper mapper, int redisPort) {
        this.mapper = mapper;
        factory = new LettuceConnectionFactory("127.0.0.1", redisPort); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        try {
            bus = new IotRedisMessageBus(redis); bus.init();
            bus.post(MQConstants.MESSAGE_BUS_IOT_GATEWAY_MESSAGE_TOPIC, new IotGatewayMessage());
            bus.register(new Heartbeats());
            bus.post("tofu:lab:traffic", new IotDeviceMessage().setMethod("lab.ready"));
            bus.register(new Traffic());
        } catch (RuntimeException e) { factory.destroy(); throw e; }
    }
    public synchronized JsonNode register(JsonNode request) {
        if (!request.isArray() || request.size() != 1 || !TofuMsProtocol.PROTOCOL_CODEC_ID.equals(request.get(0).path("codecId").asText())) {
            throw new IllegalArgumentException("Expected one ws-tofu-ms-v gateway");
        }
        com.fasterxml.jackson.databind.node.ArrayNode reply = request.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) reply.get(0)).put("iotGatewayId", GATEWAY_ID);
        serverId = reply.get(0).path("serverId").asText(); generation++; heartbeatTime = 0;
        bus.post(downTopic(), new IotDeviceMessage().setMethod("lab.ready"));
        if (!subscribed) {
            bus.post(upTopic(), new IotDeviceMessage().setMethod("lab.ready"));
            bus.register(new Reports()); subscribed = true;
        }
        return reply;
    }
    public IotDeviceRespDTO device(JsonNode request) {
        boolean byId = request.path("id").asLong(-1) == DEVICE_ID;
        boolean byName = TofuMsProtocol.PRODUCT_KEY.equals(request.path("productKey").asText())
                && DEVICE_NAME.equals(request.path("deviceName").asText());
        if (!byId && !byName) { throw new IllegalArgumentException("Unknown simulated device"); }
        return new IotDeviceRespDTO().setId(DEVICE_ID).setProductKey(TofuMsProtocol.PRODUCT_KEY).setDeviceName(DEVICE_NAME);
    }
    public synchronized void attach(RemoteGatewayControlTransport transport) {
        if (current != null) { throw new IllegalStateException("A remote target is already reserved"); }
        if (!isReady()) { throw new IllegalStateException("Start the standalone gateway and wait for its heartbeat first"); }
        current = transport;
    }
    public synchronized void detach(RemoteGatewayControlTransport transport) { if (current == transport) { current = null; } }
    public boolean isReady() { return serverId != null && System.currentTimeMillis() - heartbeatTime < 12000; }
    public long generation() { return generation; }
    public String serverId() { return serverId; }
    public StringRedisTemplate redis() { return redis; }
    public void send(IotDeviceMessage message) { bus.post(downTopic(), message); }
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>(); info.put("ready", isReady()); info.put("serverId", serverId);
        info.put("iotGatewayId", GATEWAY_ID); info.put("protocolCodecId", TofuMsProtocol.PROTOCOL_CODEC_ID);
        info.put("lastHeartbeatTime", heartbeatTime); info.put("generation", generation); return info;
    }
    private String downTopic() { return IotDeviceMessageUtils.buildTopicForMessageDownstream(String.valueOf(GATEWAY_ID)); }
    private String upTopic() { return IotDeviceMessageUtils.buildTopicForMessageUpstream(String.valueOf(GATEWAY_ID)); }
    public final class Reports implements IotMessageSubscriber<IotDeviceMessage> {
        public String getTopic() { return upTopic(); }
        public String getGroup() { return "tofu_lab_reports"; }
        public void onMessage(IotDeviceMessage message) { RemoteGatewayControlTransport target = current; if (target != null) { target.receive(message); } }
    }
    public final class Traffic implements IotMessageSubscriber<IotDeviceMessage> {
        public String getTopic() { return "tofu:lab:traffic"; }
        public String getGroup() { return "tofu_lab_traffic"; }
        public void onMessage(IotDeviceMessage message) { RemoteGatewayControlTransport target = current; if (target != null) { target.receive(message); } }
    }
    public final class Heartbeats implements IotMessageSubscriber<IotGatewayMessage> {
        public String getTopic() { return MQConstants.MESSAGE_BUS_IOT_GATEWAY_MESSAGE_TOPIC; }
        public String getGroup() { return "tofu_lab_heartbeats"; }
        public void onMessage(IotGatewayMessage message) {
            if (!com.xmkj.sdk.iot.common.enums.IotGatewayMessageMethodEnum.HEARTBEAT.getMethod().equals(message.getMethod())) { return; }
            JsonNode values = mapper.valueToTree(message.getParams());
            try { if (values.isTextual()) { values = mapper.readTree(values.asText()); } }
            catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid gateway heartbeat", e); }
            for (JsonNode value : values) {
                if (Objects.equals(serverId, value.path("serverId").asText()) && value.path("iotGatewayId").asLong() == GATEWAY_ID) {
                    heartbeatTime = System.currentTimeMillis();
                }
            }
        }
    }
    @Override public void close() { bus.destroy(); factory.destroy(); }
}
