package com.tu.deviceplatform.thingmodel.scenario;

import com.tu.deviceplatform.thingmodel.adapter.djidock.DjiDockAdapter;
import com.tu.deviceplatform.thingmodel.core.Capability;
import com.tu.deviceplatform.thingmodel.core.CapabilityType;
import com.tu.deviceplatform.thingmodel.core.StandardMessage;
import com.tu.deviceplatform.thingmodel.core.ThingModel;
import com.tu.deviceplatform.thingmodel.core.ThingModelKernel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 两组模拟数据：上行 OSD 属性上报与下行服务调用。
 */
public final class ThingModelDemo {
    private ThingModelDemo() {
    }

    public static void main(String[] args) {
        ThingModel model = dockModel();
        ThingModelKernel kernel = new ThingModelKernel();
        DjiDockAdapter adapter = new DjiDockAdapter();

        StandardMessage osdMessage = adapter.decodeOsd("thing/product/DEMO-DOCK-001/osd", osdPayload());
        kernel.accept(model, osdMessage);
        print("UPLINK_OSD_PROPERTY", osdMessage);

        StandardMessage serviceMessage = adapter.decodeService("thing/product/DEMO-DOCK-001/services",
                "cover_force_close", serviceParams());
        kernel.accept(model, serviceMessage);
        print("DOWNLINK_SERVICE", serviceMessage);
    }

    public static ThingModel dockModel() {
        return new ThingModel("demo-dock-model")
                .add(new Capability("battery_percent", CapabilityType.PROPERTY, Integer.class))
                .add(new Capability("cover_force_close", CapabilityType.SERVICE, Map.class));
    }

    private static Map<String, Object> osdPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("battery_percent", 72);
        payload.put("mode_code", 0);
        return payload;
    }

    private static Map<String, Object> serviceParams() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("requestId", "demo-request-002");
        params.put("reason", "manual-demo");
        return params;
    }

    private static void print(String title, StandardMessage message) {
        System.out.println("[" + title + "]");
        System.out.println("deviceId=" + message.getDeviceId());
        System.out.println("identifier=" + message.getIdentifier());
        System.out.println("type=" + message.getType());
        System.out.println("value=" + message.getValue());
        System.out.println("attributes=" + message.getAttributes());
    }
}
