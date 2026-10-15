package com.tu.deviceplatform.detectiondebug.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.detectiondebug.adapter.PlatformClient;
import java.util.*;

/** Session-owned, field-level config patch and recovery. No secrets/config snapshots are persisted. */
public final class SceneConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = Arrays.asList("trackingEnabled", "guidanceMode", "trackingMinElevation", "trackingMaxElevation");
    private final PlatformClient platform;
    private final String source;
    private String scene = "upstream";
    private ObjectNode previewBase, planned, originals, applied;
    private JsonNode lastCheck;

    public SceneConfiguration(PlatformClient platform, String source) { this.platform = platform; this.source = source; }

    /** Selection never modifies remote state; recovery cannot be discarded by changing scenes. */
    public void select(String value) {
        if (pending()) { throw new IllegalStateException("Restore this session's configuration before switching scenes"); }
        if (!Arrays.asList("basic", "manual", "upstream", "pointing", "tracking").contains(value)) { throw new IllegalArgumentException("Unknown scene"); }
        scene = value; previewBase = null; planned = null; lastCheck = null;
    }

    public boolean pending() { return originals != null; }

    /** Only current server evidence counts; failures clear the previous check. */
    public ObjectNode check() {
        lastCheck = null;
        JsonNode remote = platform.preflight(source);
        ObjectNode out = remote.deepCopy(); ArrayNode checks = (ArrayNode) out.path("checks");
        ObjectNode config = platform.configuration(platform.deviceDocument());
        if ("basic".equals(scene) || "manual".equals(scene)) {
            ArrayNode relevant = JSON.createArrayNode();
            for (JsonNode item : checks) { if (Arrays.asList("online", "occupancy").contains(item.path("key").asText())) { relevant.add(item); } }
            ObjectNode note = relevant.addObject(); note.put("key", "scope"); note.put("status", "WARN");
            note.put("message", "基础控制和当前位置AI不依赖坐标联动开关、白名单或安装校准；受理不代表设备动作或锁定");
            out.set("checks", relevant);
        }
        if ("pointing".equals(scene)) {
            add(checks, "connectionEnabled", config.path("enabled").asBoolean(true), "设备连接配置未关闭", "设备连接配置 enabled=false，请先由管理员处理");
            add(checks, "deviceMode", config.path("trackingEnabled").asBoolean(false) && "pointing".equals(config.path("guidanceMode").asText()),
                    "设备为只指向模式且已启用", "设备尚未启用只指向配置，请预览并应用");
            add(checks, "northOffset", finite(config.get("trackingNorthOffset"), -180, 180), "已保存初始偏向角；精度仍需现场校准", "缺少有效初始偏向角，模拟器不猜测校准值");
            add(checks, "elevation", validWindow(config), "已配置显式俯仰窗口；不代表完成机械标定", "缺少有效俯仰窗口，请填写已确认可测试的范围");
        }
        if ("tracking".equals(scene)) { add(checks, "fullTracking", false, "", "完整AI坐标联动暂不开放自动配置，需先确认机械范围和有效反馈"); }
        out.put("scene", scene); out.put("configPendingRestore", pending());
        lastCheck = out.deepCopy(); return out;
    }

    /** Preview captures only allowlisted fields; range is explicit, never inferred from protocol limits. */
    public ObjectNode preview(JsonNode body) {
        if (!"pointing".equals(scene)) { throw new IllegalStateException("Only pointing scene supports temporary configuration"); }
        if (pending()) { throw new IllegalStateException("Restore pending configuration first"); }
        previewBase = null; planned = null;
        check(); ObjectNode config = platform.configuration(platform.deviceDocument());
        if (!finite(config.get("trackingNorthOffset"), -180, 180)) { throw new IllegalStateException("Calibrate trackingNorthOffset first"); }
        ObjectNode target = JSON.createObjectNode(); target.put("trackingEnabled", true); target.put("guidanceMode", "pointing");
        target.set("trackingMinElevation", body.path("minElevation")); target.set("trackingMaxElevation", body.path("maxElevation"));
        if (!body.path("windowConfirmed").asBoolean(false) || !validWindow(target)) { throw new IllegalArgumentException("Confirm a tested elevation window: -90 <= min < max <= 90 degrees"); }
        previewBase = subset(config); planned = JSON.createObjectNode();
        for (String key : FIELDS) { if (!Objects.equals(config.get(key), target.get(key))) { planned.set(key, target.get(key)); } }
        return state();
    }

    /** Read/merge/write/readback, no CAS in existing API. Preserve recovery intent even when delivery is uncertain. */
    public ObjectNode apply() {
        if (pending() || planned == null || previewBase == null) { throw new IllegalStateException("Preview configuration first"); }
        assertFree(); ObjectNode document = platform.deviceDocument(), current = platform.configuration(document);
        if (!subset(current).equals(previewBase)) { throw new IllegalStateException("Scene fields changed since preview; preview again"); }
        if (planned.size() == 0) { return state(); }
        originals = JSON.createObjectNode(); applied = planned.deepCopy();
        planned.fieldNames().forEachRemaining(key -> { if (current.has(key)) { originals.set(key, current.get(key)); } });
        planned.fields().forEachRemaining(e -> current.set(e.getKey(), e.getValue()));
        platform.updateConfiguration(document, current);
        verify(applied); previewBase = null; planned = null; lastCheck = null;
        return state();
    }

    /** Wait for platform ownership release; never stop someone else's automatic task or blindly restore full JSON. */
    public ObjectNode restore() {
        if (!pending()) { return state(); }
        assertFree(); ObjectNode document = platform.deviceDocument(), current = platform.configuration(document);
        Iterator<String> keys = applied.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next(); JsonNode value = current.get(key);
            if (!Objects.equals(value, applied.get(key)) && !Objects.equals(value, originals.get(key))) {
                throw new IllegalStateException("Config conflict at " + key + "; recovery retained, coordinate with the other operator");
            }
        }
        applied.fieldNames().forEachRemaining(key -> { if (originals.has(key)) { current.set(key, originals.get(key)); } else { current.remove(key); } });
        platform.updateConfiguration(document, current);
        ObjectNode saved = platform.configuration(platform.deviceDocument());
        Iterator<String> verifyKeys = applied.fieldNames();
        while (verifyKeys.hasNext()) { String key = verifyKeys.next(); if (!Objects.equals(saved.get(key), originals.get(key))) { throw new IllegalStateException("Restore readback mismatch; recovery retained"); } }
        originals = null; applied = null; previewBase = null; planned = null; lastCheck = null; return state();
    }

    /** Actual source publishing is gated by a fresh preflight, including upstream-only isolation. */
    public void requirePointScene() {
        if (!"pointing".equals(scene) && !"upstream".equals(scene)) { throw new IllegalStateException("Select upstream-only or coordinate pointing scene"); }
        ObjectNode result = check();
        if ("upstream".equals(scene)) {
            JsonNode global = find(result, "global");
            if (global == null || !"BLOCK".equals(global.path("status").asText())) { throw new IllegalStateException("Upstream-only requires global linkage OFF; otherwise points may move cameras"); }
        } else {
            for (JsonNode item : result.path("checks")) { if ("BLOCK".equals(item.path("status").asText())) { throw new IllegalStateException(item.path("message").asText()); } }
            for (String required : Arrays.asList("global", "whitelist", "online", "occupancy", "installation", "source", "sourceZone", "connectionEnabled", "deviceMode", "northOffset", "elevation")) {
                JsonNode item = find(result, required);
                if (item == null || !"PASS".equals(item.path("status").asText())) { throw new IllegalStateException("Required preflight check unavailable: " + required); }
            }
        }
    }

    public ObjectNode state() {
        ObjectNode out = JSON.createObjectNode(); out.put("selected", scene); out.put("pendingRestore", pending());
        if (lastCheck != null) { out.set("preflight", lastCheck.deepCopy()); }
        ArrayNode changes = out.putArray("changes");
        ObjectNode values = pending() ? applied : planned;
        ObjectNode before = pending() ? originals : previewBase;
        if (values != null) { values.fields().forEachRemaining(e -> {
            ObjectNode row = changes.addObject(); row.put("field", e.getKey()); row.put("originalPresent", before.has(e.getKey()));
            if (before.has(e.getKey())) { row.set("before", before.get(e.getKey())); } row.set("after", e.getValue());
        }); }
        out.put("note", "配置回读仅证明已保存；不证明网关已应用或设备动作。恢复快照仅在内存，恢复前请勿重启。更新接口无原子版本校验，请避免并发编辑设备配置。");
        return out;
    }

    private void assertFree() {
        JsonNode result = platform.preflight(source);
        if (!result.path("occupied").isBoolean()) { throw new IllegalStateException("Task occupancy unavailable; cannot change configuration"); }
        if (result.path("occupied").asBoolean() || !result.path("activeTrackingId").asText("").isEmpty()) {
            throw new IllegalStateException("Platform task still active; source stop does not stop that task. Wait for release, then restore");
        }
    }
    private void verify(ObjectNode expected) {
        ObjectNode actual = platform.configuration(platform.deviceDocument());
        Iterator<String> keys = expected.fieldNames();
        while (keys.hasNext()) { String key = keys.next(); if (!Objects.equals(expected.get(key), actual.get(key))) { throw new IllegalStateException("Apply readback mismatch; recovery retained"); } }
    }
    private static ObjectNode subset(ObjectNode value) { ObjectNode out = JSON.createObjectNode(); for (String key : FIELDS) { if (value.has(key)) { out.set(key, value.get(key)); } } return out; }
    private static boolean validWindow(JsonNode value) { return finite(value.get("trackingMinElevation"), -90, 90) && finite(value.get("trackingMaxElevation"), -90, 90) && value.path("trackingMinElevation").asDouble() < value.path("trackingMaxElevation").asDouble(); }
    private static boolean finite(JsonNode value, double min, double max) { return value != null && value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= min && value.asDouble() <= max; }
    private static JsonNode find(JsonNode result, String key) { for (JsonNode item : result.path("checks")) { if (key.equals(item.path("key").asText())) { return item; } } return null; }
    private static void add(ArrayNode checks, String key, boolean pass, String yes, String no) { ObjectNode item = checks.addObject(); item.put("key", key); item.put("status", pass ? "PASS" : "BLOCK"); item.put("message", pass ? yes : no); }
}
