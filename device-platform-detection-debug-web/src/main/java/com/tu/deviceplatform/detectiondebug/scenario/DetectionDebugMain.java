package com.tu.deviceplatform.detectiondebug.scenario;

import com.tu.deviceplatform.detectiondebug.adapter.*;
import com.tu.deviceplatform.detectiondebug.core.DebugSession;
import java.util.concurrent.CountDownLatch;

/** Environment-only deployment configuration; startup does not log in, publish or send controls. */
public final class DetectionDebugMain {
    private DetectionDebugMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.out.println("Configure DEBUG_PLATFORM_BASE (ending /admin-api), DEBUG_CAMERA_ID, DEBUG_DEVICE_NO, DEBUG_PUBLIC_ORIGIN, DEBUG_MQTT_BROKER; optional DEBUG_CHANNEL, DEBUG_BIND, DEBUG_PORT, DEBUG_MQTT_USERNAME, DEBUG_MQTT_PASSWORD. No action on startup."); return;
        }
        PlatformClient platform = new PlatformClient(required("DEBUG_PLATFORM_BASE"), Long.parseLong(required("DEBUG_CAMERA_ID")), env("DEBUG_CHANNEL", "1"));
        try (NtXr1Publisher publisher = new NtXr1Publisher(required("DEBUG_MQTT_BROKER"), env("DEBUG_MQTT_USERNAME", ""), env("DEBUG_MQTT_PASSWORD", ""));
             DebugSession session = new DebugSession(platform, required("DEBUG_DEVICE_NO"), publisher::publish);
             DebugWebServer web = new DebugWebServer(session, env("DEBUG_BIND", "127.0.0.1"), Integer.parseInt(env("DEBUG_PORT", "18091")), required("DEBUG_PUBLIC_ORIGIN"), env("DEBUG_DEFAULT_ZONE", ""))) {
            Runtime.getRuntime().addShutdownHook(new Thread(web::close, "debug-web-shutdown"));
            System.out.println("Detection debug web ready; no commands or simulated points sent on startup.");
            new CountDownLatch(1).await();
        }
    }
    private static String required(String key) {
        String value = System.getenv(key); if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(key + " required"); } return value;
    }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null ? fallback : value; }
}
