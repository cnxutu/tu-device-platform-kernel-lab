package com.tu.deviceplatform.tofumsweb.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import com.tu.deviceplatform.tofumsweb.core.GatewayWebLab;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same-origin loopback HTTP endpoint for the isolated gateway lab. */
public final class GatewayWebServer implements AutoCloseable {
    private static final int MAX_BODY = 8192;
    private final GatewayWebLab lab;
    private final HttpServer server;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());

    public GatewayWebServer(GatewayWebLab lab, int port) throws IOException {
        this.lab = lab;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                if ("/api/catalog".equals(path)) { json(exchange, 200, lab.catalog()); }
                else if ("/api/state".equals(path)) {
                    String query = exchange.getRequestURI().getRawQuery();
                    long cursor = 0;
                    if (query != null) {
                        if (!query.matches("cursor=[0-9]{1,18}")) { throw new IllegalArgumentException("Expected nonnegative cursor"); }
                        cursor = Long.parseLong(query.substring(7));
                    }
                    json(exchange, 200, lab.state(cursor));
                } else if ("/".equals(path) || "/app.js".equals(path) || "/style.css".equals(path)) {
                    staticFile(exchange, "/".equals(path) ? "index.html" : path.substring(1));
                } else { json(exchange, 404, Collections.singletonMap("error", "Unknown endpoint")); }
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, Collections.singletonMap("error", "GET or POST required")); return; }
            // JSON-only mutations and same-origin browser requests; no cross-origin access.
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://127.0.0.1:" + port())) { json(exchange, 403, Collections.singletonMap("error", "Same-origin request required")); return; }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new IllegalArgumentException("application/json required");
            }
            JsonNode body = lab.mapper().readTree(readLimited(exchange.getRequestBody(), MAX_BODY));
            if (body == null || !body.isObject()) { throw new IllegalArgumentException("JSON object required"); }
            switch (path) {
                case "/api/connect": json(exchange, 200, lab.connect(body)); return;
                case "/api/invoke": GatewayWebLab.await(lab.invoke(body)); break;
                case "/api/stop": requireEmpty(body); GatewayWebLab.await(lab.stopAll()); break;
                case "/api/disconnect": requireEmpty(body); lab.disconnect(); break;
                default: json(exchange, 404, Collections.singletonMap("error", "Unknown endpoint")); return;
            }
            json(exchange, 200, lab.state(0));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            json(exchange, 400, Collections.singletonMap("error", e instanceof JsonProcessingException ? "Invalid JSON" : e.getMessage()));
        } catch (IllegalStateException e) {
            json(exchange, 409, Collections.singletonMap("error", e.getMessage()));
        } catch (RuntimeException e) {
            json(exchange, 500, Collections.singletonMap("error", "Web lab internal failure: " + e.getClass().getSimpleName()));
            e.printStackTrace(System.err);
        } finally { exchange.close(); }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", lab.mapper().writeValueAsBytes(value));
    }
    private void staticFile(HttpExchange exchange, String file) throws IOException {
        try (InputStream input = GatewayWebServer.class.getResourceAsStream("/web/" + file)) {
            if (input == null) { throw new IllegalStateException("Missing Web resource"); }
            send(exchange, 200, file.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8", readLimited(input, 128 * 1024));
        }
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }
    private static byte[] readLimited(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int length;
        while ((length = input.read(buffer)) != -1) {
            if (output.size() + length > max) { throw new IllegalArgumentException("Request exceeds size limit"); }
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }
    private static void requireEmpty(JsonNode node) { if (node.size() != 0) { throw new IllegalArgumentException("No parameters expected"); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        server.stop(0); workers.shutdownNow(); lab.close();
    }
}
