package com.tu.deviceplatform.detectiondebug.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Lazy MQTT publisher; never connects or publishes until explicitly requested. */
public final class NtXr1Publisher implements AutoCloseable {
    private final String broker, username, password;
    private MqttClient client;
    public NtXr1Publisher(String broker, String username, String password) {
        this.broker = broker; this.username = username; this.password = password;
    }
    public synchronized void publish(String topic, JsonNode body) {
        try {
            if (client == null) {
                if (broker == null || broker.isEmpty()) { throw new IllegalStateException("MQTT not configured"); }
                client = new MqttClient(broker, "debug-" + UUID.randomUUID(), new MemoryPersistence());
                client.setTimeToWait(4000);
            }
            if (!client.isConnected()) {
                MqttConnectOptions options = new MqttConnectOptions(); options.setCleanSession(true);
                options.setAutomaticReconnect(false); options.setConnectionTimeout(3); options.setKeepAliveInterval(15);
                if (username != null && !username.isEmpty()) { options.setUserName(username); }
                if (password != null && !password.isEmpty()) { options.setPassword(password.toCharArray()); }
                client.connect(options);
            }
            // No retained target, no retry of a possibly delivered point. PUBACK is not business acceptance.
            client.publish(topic, body.toString().getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (MqttException e) { throw new IllegalStateException("MQTT publish failed, reason=" + e.getReasonCode() + "; delivery may be unknown"); }
    }
    @Override public synchronized void close() {
        if (client == null) { return; }
        try { if (client.isConnected()) { client.disconnect(1000); } client.close(); }
        catch (MqttException e) { throw new IllegalStateException("MQTT close failed, reason=" + e.getReasonCode()); }
        finally { client = null; }
    }
}
