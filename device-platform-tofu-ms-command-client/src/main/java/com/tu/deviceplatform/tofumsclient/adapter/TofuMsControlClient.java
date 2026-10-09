package com.tu.deviceplatform.tofumsclient.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofumsclient.core.OpticalControlTransport;
import com.xmkj.codec.adapter.uav.device.tofums.TofuMsCustomProtocolAdapter;
import com.xmkj.codec.adapter.uav.device.tofums.command.TofuMsCommandRegistry;
import com.xmkj.codec.adapter.uav.device.tofums.mapper.TofuMsUpstreamMapper;
import com.xmkj.sdk.iot.codec.adapter.upstream.DeviceInfo;
import com.xmkj.sdk.iot.codec.adapter.upstream.UpstreamDecodedData;
import com.xmkj.sdk.iot.common.biz.dto.IotDeviceConnectionDTO;
import com.xmkj.sdk.iot.common.mq.message.IotDeviceMessage;
import com.xmkj.sdk.iot.protocol.adapter.CustomProtocolAdapterCallback;
import com.xmkj.sdk.iot.protocol.adapter.device.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** Runs the production adapter with a local, fictitious device identity and no platform infrastructure. */
public final class TofuMsControlClient implements OpticalControlTransport {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TofuMsCustomProtocolAdapter adapter;
    private final Consumer<String> output;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final DeviceInfo identity = new DeviceInfo().setProductKey("lab-optical").setDeviceName("lab-command-client");

    public TofuMsControlClient(String host, int port, int address, Consumer<String> output) throws Exception {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535 || address < 1 || address > 255) {
            throw new IllegalArgumentException("Host, port 1..65535 and PTZ address 1..255 required");
        }
        this.output = output;
        adapter = new TofuMsCustomProtocolAdapter(mapper, new TofuMsCommandRegistry(mapper), new TofuMsUpstreamMapper(mapper));
        adapter.setTrafficObserver((id, outbound, frame) -> {
            int type = frame[2] & 255;
            String detail = (type == 1 || type == 3) ? " json=" + new String(frame, 7, frame.length - 7, StandardCharsets.UTF_8) : "";
            output.accept((outbound ? "TX_FRAME" : "RX_FRAME") + " hex=" + hex(frame) + detail);
        });
        adapter.registerCallback(new CustomProtocolAdapterCallback() {
            @Override public void upstream(UpstreamDecodedData data) { output.accept("UPSTREAM " + json(data)); }
            @Override public void statusChanged(DeviceConnectionStatus status) {
                connectionGeneration.incrementAndGet();
                output.accept("CONNECTION " + status.getState());
            }
        });
        adapter.init(Collections.singletonMap("commandIntervalMillis", "150"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", host); config.put("port", port); config.put("ptzAddress", address);
        IotDeviceConnectionDTO device = new IotDeviceConnectionDTO().setDeviceId(7001L)
                .setProductKey(identity.getProductKey()).setDeviceName(identity.getDeviceName()).setIp(host).setConfig(mapper.writeValueAsString(config));
        adapter.syncDeviceConnection(new DeviceConnectionChange().setType(DeviceConnectionChangeType.ADDED).setAfter(device));
    }

    public boolean isOnline() { return adapter.getConnectionCount() == 1; }
    public long getConnectionGeneration() { return connectionGeneration.get(); }

    public void send(String identifier, int action, Integer speed) {
        Map<String, Object> params = new LinkedHashMap<>(); params.put("channelId", "1"); params.put("action", action);
        if (speed != null) { params.put("speedPercent", speed); }
        Map<String, Object> envelope = new LinkedHashMap<>(); envelope.put("identifier", identifier); envelope.put("params", params);
        IotDeviceMessage message = new IotDeviceMessage().setDeviceId(7001L).setRequestId(UUID.randomUUID().toString())
                .setMethod("thing.service.invoke").setParams(envelope);
        output.accept("SERVICE requestId=" + message.getRequestId() + " " + json(envelope));
        output.accept("RESULT " + json(adapter.downstream(identity, message)) + " (TCP write only; physical execution unconfirmed)");
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot display control message", e); }
    }
    private static String hex(byte[] frame) {
        StringBuilder result = new StringBuilder(frame.length * 2);
        for (byte b : frame) { result.append(String.format("%02X", b & 255)); }
        return result.toString();
    }
    @Override public void close() { adapter.close(); }
}
