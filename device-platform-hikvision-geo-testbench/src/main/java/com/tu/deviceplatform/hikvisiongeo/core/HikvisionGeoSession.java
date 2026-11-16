package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.hikvisiongeo.core.GeoPointGenerator.Point;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** In-memory testbench session. Imported request bodies are never persisted or logged. */
public final class HikvisionGeoSession implements AutoCloseable {
    private static final long HEARTBEAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_RUN_SECONDS = 600;

    private final ObjectMapper mapper;
    private final ServiceRequestLoader loader;
    private final ServiceInvoker invoker;
    private final HikvisionCommandPreview commandPreview;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();
    private final List<ObjectNode> requests = new ArrayList<ObjectNode>();

    private int selectedIndex = -1;
    private boolean running;
    private boolean inFlight;
    private long runId;
    private long runStartedNanos;
    private long lastHeartbeatNanos;
    private int maxRunSeconds;
    private int sentCount;
    private String loopMode = "";
    private String loopMessage = "尚未开始";
    private Point loopCenter;
    private double loopRadiusMeters;
    private String loopIdentifier;
    private int loopRequestIndex;
    private List<Point> cardinalPoints = Collections.emptyList();
    private Point lastPoint;
    private ServiceInvokeResult lastResult;
    private ObjectNode lastRequest;
    private HikvisionCommandPreview.Preview lastDeviceCommand;
    private ScheduledFuture<?> scheduled;

    public HikvisionGeoSession(ObjectMapper mapper, ServiceInvoker invoker,
                               HikvisionCommandPreview commandPreview) {
        this.mapper = mapper;
        this.loader = new ServiceRequestLoader(mapper);
        this.invoker = invoker;
        this.commandPreview = commandPreview;
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "hikvision-geo-p2-loop");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    }

    public synchronized ObjectNode configureP2(String baseUrl) {
        if (running || inFlight) {
            throw new IllegalStateException("Stop the current P2 action before changing the destination");
        }
        if (!(invoker instanceof ConfigurableServiceInvoker)) {
            throw new IllegalStateException("P2 destination cannot be changed at runtime");
        }
        ((ConfigurableServiceInvoker) invoker).configureBaseUrl(baseUrl);
        return config();
    }

    public synchronized ObjectNode config() {
        ObjectNode result = mapper.createObjectNode();
        result.put("p2Configured", invoker.isConfigured());
        result.put("p2BaseUrl", invoker.getBaseUrl());
        result.put("p2RpcPath", "/rpc-api/iot/devices/service/invoke");
        result.put("authorizationConfigured", invoker instanceof ConfigurableServiceInvoker
                && ((ConfigurableServiceInvoker) invoker).hasAuthorization());
        return result;
    }

    public synchronized ObjectNode importRequests(String input) {
        requireIdle();
        List<ObjectNode> parsed = loader.parse(input);
        requests.clear();
        for (ObjectNode request : parsed) {
            requests.add(request.deepCopy());
        }
        selectedIndex = requests.isEmpty() ? -1 : 0;
        sentCount = 0;
        lastPoint = null;
        lastResult = null;
        lastRequest = null;
        lastDeviceCommand = null;
        loopMessage = "已载入 " + requests.size() + " 条请求；尚未发送";
        return state();
    }

    public synchronized ObjectNode selectRequest(int index) {
        requireIdle();
        requestAt(index);
        selectedIndex = index;
        sentCount = 0;
        lastPoint = null;
        lastResult = null;
        lastRequest = null;
        lastDeviceCommand = null;
        loopMessage = "已选择请求；尚未发送";
        return state();
    }

    public synchronized ObjectNode preview(JsonNode options) {
        ObjectNode request = createRequest(options);
        HikvisionCommandPreview.Preview device = commandPreview.create(request);
        ObjectNode result = mapper.createObjectNode();
        result.set("p2Request", request);
        ObjectNode deviceCommand = mapper.createObjectNode();
        deviceCommand.put("path", device.getPath());
        deviceCommand.put("xml", device.getXml());
        deviceCommand.put("curl", device.getCurl());
        result.set("deviceCommand", deviceCommand);
        result.put("p2DestinationConfigured", invoker.isConfigured());
        return result;
    }

    public synchronized ObjectNode directionPoint(JsonNode options) {
        if (options == null || !options.isObject()) {
            throw new IllegalArgumentException("Direction options must be an object");
        }
        Point center = pointFrom(options);
        double radius = number(options, "radiusMeters").doubleValue();
        String direction = options.path("direction").asText("");
        Point point = GeoPointGenerator.direction(center, radius, direction);
        ObjectNode result = mapper.createObjectNode();
        result.put("direction", direction);
        result.set("point", pointNode(point));
        return result;
    }

    public synchronized ObjectNode randomCardinalPoint(JsonNode options) {
        if (options == null || !options.isObject()) {
            throw new IllegalArgumentException("Direction options must be an object");
        }
        Point center = pointFrom(options);
        double radius = number(options, "radiusMeters").doubleValue();
        String direction = options.path("direction").asText("");
        Point point = GeoPointGenerator.randomAlongCardinal(center, radius, direction, random);
        ObjectNode result = mapper.createObjectNode();
        result.put("direction", direction);
        result.put("distanceMeters", GeoPointGenerator.distanceMeters(center, point));
        result.put("radiusMeters", radius);
        result.set("point", pointNode(point));
        return result;
    }

    public ObjectNode sendOnce(JsonNode options) {
        return sendOnce(options, false);
    }

    public ObjectNode sendRandomOnce(JsonNode options) {
        return sendOnce(options, true);
    }

    private ObjectNode sendOnce(JsonNode options, boolean randomWithinRange) {
        ObjectNode request;
        synchronized (this) {
            if (running || inFlight) {
                throw new IllegalStateException("A P2 action is already running");
            }
            if (!invoker.isConfigured()) {
                throw new IllegalStateException("Configure the P2 base URL before sending");
            }
            request = randomWithinRange ? createRandomRequest(options) : createRequest(options);
            setLastCommand(request);
            inFlight = true;
        }
        ServiceInvokeResult result = invokeSafely(request);
        synchronized (this) {
            inFlight = false;
            sentCount++;
            lastResult = result;
            lastPoint = ServiceRequestFactory.point(request);
            loopMessage = resultMessage(result);
            return invocation(result, request, lastPoint);
        }
    }

    public synchronized ObjectNode startLoop(JsonNode options) {
        if (running || inFlight) {
            throw new IllegalStateException("A P2 action is already running");
        }
        if (!invoker.isConfigured()) {
            throw new IllegalStateException("Configure the P2 base URL before sending");
        }
        int index = options.path("index").asInt(selectedIndex);
        ObjectNode prototype = requestAt(index);
        String identifier = identifier(options.path("identifier").asText(""));
        String mode = options.path("mode").asText("");
        if (!("random".equals(mode) || "cardinal".equals(mode))) {
            throw new IllegalArgumentException("mode must be random or cardinal");
        }
        Point center = pointFrom(options);
        GeoPointGenerator.validate(center);
        double radius = number(options, "radiusMeters").doubleValue();
        GeoPointGenerator.validateRadius(radius);
        int duration = options.path("maxDurationSeconds").asInt(60);
        if (duration < 1 || duration > MAX_RUN_SECONDS) {
            throw new IllegalArgumentException("maxDurationSeconds must be between 1 and 600");
        }
        if (prototype.path("deviceId").asLong() < 1) {
            throw new IllegalArgumentException("The selected request has an invalid deviceId");
        }

        stopScheduled();
        runId++;
        long currentRunId = runId;
        running = true;
        inFlight = false;
        loopMode = mode;
        loopMessage = "正在启动";
        loopIdentifier = identifier;
        loopRequestIndex = index;
        loopCenter = center;
        loopRadiusMeters = radius;
        maxRunSeconds = duration;
        runStartedNanos = System.nanoTime();
        lastHeartbeatNanos = runStartedNanos;
        sentCount = 0;
        lastPoint = null;
        lastResult = null;
        lastRequest = null;
        lastDeviceCommand = null;
        cardinalPoints = "cardinal".equals(mode)
                ? GeoPointGenerator.cardinal(center, radius) : Collections.<Point>emptyList();
        scheduled = scheduler.scheduleWithFixedDelay(() -> runTick(currentRunId), 0, 1, TimeUnit.SECONDS);
        return state();
    }

    public synchronized ObjectNode stopLoop() {
        stop("已由用户停止");
        return state();
    }

    public synchronized ObjectNode heartbeat() {
        if (running) {
            lastHeartbeatNanos = System.nanoTime();
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("running", running);
        return result;
    }

    public synchronized ObjectNode state() {
        ObjectNode state = mapper.createObjectNode();
        state.put("p2Configured", invoker.isConfigured());
        state.put("p2BaseUrl", invoker.getBaseUrl());
        state.put("selectedIndex", selectedIndex);
        state.put("running", running);
        state.put("inFlight", inFlight);
        state.put("loopMode", loopMode);
        state.put("loopMessage", loopMessage);
        state.put("sentCount", sentCount);
        state.put("maxRunSeconds", maxRunSeconds);
        state.put("radiusMeters", loopRadiusMeters);
        state.put("requestCount", requests.size());
        ArrayNode items = state.putArray("requests");
        for (int i = 0; i < requests.size(); i++) {
            items.add(requestSummary(i, requests.get(i)));
        }
        if (lastPoint != null) {
            ObjectNode point = state.putObject("lastPoint");
            putPoint(point, lastPoint);
        }
        if (lastResult != null) {
            state.set("lastResult", resultNode(lastResult));
        }
        if (lastRequest != null && lastDeviceCommand != null) {
            ObjectNode preview = state.putObject("lastPreview");
            preview.set("p2Request", lastRequest.deepCopy());
            ObjectNode deviceCommand = preview.putObject("deviceCommand");
            deviceCommand.put("path", lastDeviceCommand.getPath());
            deviceCommand.put("xml", lastDeviceCommand.getXml());
            deviceCommand.put("curl", lastDeviceCommand.getCurl());
        }
        return state;
    }

    private void runTick(long currentRunId) {
        ObjectNode request;
        Point point;
        synchronized (this) {
            if (!running || currentRunId != runId) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastHeartbeatNanos > HEARTBEAT_TIMEOUT_NANOS) {
                stop("页面心跳超时，已自动停止后续下发");
                return;
            }
            if (now - runStartedNanos >= TimeUnit.SECONDS.toNanos(maxRunSeconds)) {
                stop("达到本次运行时长上限，已自动停止");
                return;
            }
            if (inFlight) {
                return;
            }
            if ("cardinal".equals(loopMode)) {
                if (sentCount >= cardinalPoints.size()) {
                    stop("四方位序列已完成");
                    return;
                }
                point = cardinalPoints.get(sentCount);
            } else {
                point = GeoPointGenerator.randomWithin(loopCenter, loopRadiusMeters, random);
            }
            try {
                request = ServiceRequestFactory.withPoint(requestAt(loopRequestIndex), loopIdentifier, point);
                setLastCommand(request);
            } catch (RuntimeException e) {
                stop("请求预览生成失败（" + e.getClass().getSimpleName() + "），循环已停止");
                return;
            }
            inFlight = true;
        }

        ServiceInvokeResult result = invokeSafely(request);
        synchronized (this) {
            inFlight = false;
            if (currentRunId != runId) {
                return;
            }
            sentCount++;
            lastPoint = point;
            lastResult = result;
            loopMessage = resultMessage(result);
            if (!running) {
                return;
            }
            if (!result.isAccepted()) {
                stop("P2 请求未成功，循环已停止");
            } else if ("cardinal".equals(loopMode) && sentCount >= cardinalPoints.size()) {
                stop("四方位序列已完成");
            }
        }
    }

    private ObjectNode createRequest(JsonNode options) {
        if (options == null || !options.isObject()) {
            throw new IllegalArgumentException("Request options must be an object");
        }
        int index = options.path("index").asInt(selectedIndex);
        String operation = identifier(options.path("identifier").asText(""));
        ObjectNode request = ServiceRequestFactory.withPoint(requestAt(index), operation, pointFrom(options));
        return loader.validate(request);
    }

    private ObjectNode createRandomRequest(JsonNode options) {
        if (options == null || !options.isObject()) {
            throw new IllegalArgumentException("Request options must be an object");
        }
        int index = options.path("index").asInt(selectedIndex);
        String operation = identifier(options.path("identifier").asText(""));
        Point center = pointFrom(options);
        GeoPointGenerator.validate(center);
        double radius = number(options, "radiusMeters").doubleValue();
        GeoPointGenerator.validateRadius(radius);
        Point point = GeoPointGenerator.randomWithin(center, radius, random);
        return loader.validate(ServiceRequestFactory.withPoint(requestAt(index), operation, point));
    }

    private Point pointFrom(JsonNode node) {
        return new Point(number(node, "longitude"), number(node, "latitude"), number(node, "altitude"));
    }

    private BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return value.decimalValue();
    }

    private String identifier(String value) {
        if (!("target_acquire".equals(value) || "target_follow".equals(value))) {
            throw new IllegalArgumentException("identifier must be target_acquire or target_follow");
        }
        return value;
    }

    private ObjectNode requestAt(int index) {
        if (index < 0 || index >= requests.size()) {
            throw new IllegalArgumentException("Select an imported service request first");
        }
        return requests.get(index);
    }

    private ObjectNode requestSummary(int index, ObjectNode request) {
        ObjectNode item = mapper.createObjectNode();
        item.put("index", index);
        item.put("deviceId", request.path("deviceId").asLong());
        item.put("identifier", request.path("identifier").asText());
        item.put("targetId", request.path("params").path("targetId").asText());
        item.set("geo", request.path("params").path("position").path("geo").deepCopy());
        return item;
    }

    private ObjectNode invocation(ServiceInvokeResult result, ObjectNode request, Point point) {
        ObjectNode value = mapper.createObjectNode();
        value.set("request", request);
        value.set("point", pointNode(point));
        value.set("result", resultNode(result));
        if (lastDeviceCommand != null) {
            ObjectNode command = value.putObject("deviceCommand");
            command.put("path", lastDeviceCommand.getPath());
            command.put("xml", lastDeviceCommand.getXml());
            command.put("curl", lastDeviceCommand.getCurl());
        }
        return value;
    }

    private void setLastCommand(ObjectNode request) {
        lastRequest = request.deepCopy();
        lastDeviceCommand = commandPreview.create(request);
    }

    private ObjectNode resultNode(ServiceInvokeResult result) {
        ObjectNode value = mapper.createObjectNode();
        value.put("httpStatus", result.getHttpStatus());
        value.put("accepted", result.isAccepted());
        value.put("requestId", result.getRequestId());
        if (result.getTransportError() != null) {
            value.put("transportError", result.getTransportError());
        }
        value.put("responseBody", result.getResponseBody());
        return value;
    }

    private ObjectNode pointNode(Point point) {
        ObjectNode value = mapper.createObjectNode();
        putPoint(value, point);
        return value;
    }

    private void putPoint(ObjectNode value, Point point) {
        value.put("longitude", point.getLongitude());
        value.put("latitude", point.getLatitude());
        value.put("altitude", point.getAltitude());
    }

    private String resultMessage(ServiceInvokeResult result) {
        if (result.getTransportError() != null) {
            return "P2 连接失败（" + result.getTransportError() + "）";
        }
        if (result.isAccepted()) {
            return "P2 接口返回成功；这不代表设备已物理转向";
        }
        return "P2 返回失败（HTTP " + result.getHttpStatus() + "）；请检查响应并停止测试";
    }

    private ServiceInvokeResult invokeSafely(ObjectNode request) {
        try {
            return invoker.invoke(request);
        } catch (RuntimeException e) {
            return new ServiceInvokeResult(-1, false, "", "", e.getClass().getSimpleName());
        }
    }

    private void requireIdle() {
        if (running || inFlight) {
            throw new IllegalStateException("Stop the current P2 action before changing imported requests");
        }
    }

    private void stop(String message) {
        running = false;
        loopMessage = inFlight ? message + "；当前 P2 请求仍在等待返回" : message;
        stopScheduled();
    }

    private void stopScheduled() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    @Override
    public synchronized void close() {
        stop("测试台已关闭");
        scheduler.shutdownNow();
    }
}
