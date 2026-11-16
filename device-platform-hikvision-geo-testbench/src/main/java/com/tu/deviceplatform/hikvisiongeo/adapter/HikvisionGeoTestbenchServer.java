package com.tu.deviceplatform.hikvisiongeo.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tu.deviceplatform.hikvisiongeo.core.HikvisionGeoSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Same-origin web UI and local API, bound to loopback only. */
public final class HikvisionGeoTestbenchServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 300 * 1024;
    private final ObjectMapper mapper;
    private final HikvisionGeoSession session;
    private final HttpServer server;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(24), new ThreadPoolExecutor.AbortPolicy());

    public HikvisionGeoTestbenchServer(ObjectMapper mapper, HikvisionGeoSession session, int port) throws IOException {
        this.mapper = mapper;
        this.session = session;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host != null && !host.matches("(?i)(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?")) {
                json(exchange, 403, error("Loopback Host required"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                if ("/favicon.ico".equals(path)) { exchange.sendResponseHeaders(204, -1); return; }
                if ("/api/config".equals(path)) { json(exchange, 200, session.config()); return; }
                if ("/api/state".equals(path)) { json(exchange, 200, session.state()); return; }
                if ("/health".equals(path)) { json(exchange, 200, Collections.singletonMap("status", "UP")); return; }
                if ("/".equals(path) || "/app.js".equals(path) || "/style.css".equals(path)) {
                    staticFile(exchange, "/".equals(path) ? "index.html" : path.substring(1));
                    return;
                }
                json(exchange, 404, error("Unknown endpoint"));
                return;
            }
            if (!"POST".equals(method)) {
                json(exchange, 405, error("GET or POST required"));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String expected127 = "http://127.0.0.1:" + port();
            String expectedLocalhost = "http://localhost:" + port();
            if (origin != null && !expected127.equals(origin) && !expectedLocalhost.equals(origin)) {
                json(exchange, 403, error("Same-origin request required"));
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new IllegalArgumentException("application/json required");
            }
            JsonNode body = mapper.readTree(readLimited(exchange.getRequestBody(), MAX_BODY_BYTES));
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("JSON object required");
            }
            switch (path) {
                case "/api/config":
                    json(exchange, 200, session.configureP2(body.path("baseUrl").asText("")));
                    return;
                case "/api/import":
                    json(exchange, 200, session.importRequests(body.path("text").asText("")));
                    return;
                case "/api/select":
                    json(exchange, 200, session.selectRequest(body.path("index").asInt(-1)));
                    return;
                case "/api/preview":
                    json(exchange, 200, session.preview(body));
                    return;
                case "/api/direction-point":
                    json(exchange, 200, session.directionPoint(body));
                    return;
                case "/api/random-cardinal-point":
                    json(exchange, 200, session.randomCardinalPoint(body));
                    return;
                case "/api/send":
                    json(exchange, 200, session.sendOnce(body));
                    return;
                case "/api/send-random":
                    json(exchange, 200, session.sendRandomOnce(body));
                    return;
                case "/api/start":
                    json(exchange, 200, session.startLoop(body));
                    return;
                case "/api/stop":
                    json(exchange, 200, session.stopLoop());
                    return;
                case "/api/heartbeat":
                    json(exchange, 200, session.heartbeat());
                    return;
                default:
                    json(exchange, 404, error("Unknown endpoint"));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            json(exchange, 400, error(e instanceof JsonProcessingException ? "Invalid JSON" : e.getMessage()));
        } catch (IllegalStateException e) {
            json(exchange, 409, error(e.getMessage()));
        } catch (RuntimeException e) {
            json(exchange, 500, error("Testbench request failed"));
        } finally {
            exchange.close();
        }
    }

    private Object error(String message) {
        return Collections.singletonMap("error", message);
    }

    private void staticFile(HttpExchange exchange, String filename) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/web/" + filename)) {
            if (input == null) {
                json(exchange, 404, error("Web resource is missing"));
                return;
            }
            String type = filename.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : filename.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
            send(exchange, 200, type, readLimited(input, 128 * 1024));
        }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", mapper.writeValueAsBytes(value));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; connect-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = closeable.read(buffer)) != -1) {
                if (output.size() + length > limit) {
                    throw new IllegalArgumentException("Request exceeds size limit");
                }
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        session.close();
    }
}
