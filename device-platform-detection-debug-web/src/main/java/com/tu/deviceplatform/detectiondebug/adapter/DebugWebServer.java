package com.tu.deviceplatform.detectiondebug.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import com.tu.deviceplatform.detectiondebug.core.DebugSession;
import java.io.*;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Thin same-origin server; never exposes generic platform/MQTT forwarding or stores credentials on disk. */
public final class DebugWebServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final DebugSession session;
    private final HttpServer server;
    private final String origin;
    private final String defaultZone;
    private final String sessionId;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24), new ThreadPoolExecutor.AbortPolicy());

    public DebugWebServer(DebugSession session, String bind, int port, String origin) throws IOException {
        this(session, bind, port, origin, "");
    }

    /** Default zone is deployment-supplied and remains editable; it never changes platform bindings. */
    public DebugWebServer(DebugSession session, String bind, int port, String origin, String defaultZone) throws IOException {
        this.session = session; this.origin = origin;
        if (defaultZone == null || !defaultZone.matches("[A-Za-z0-9_,\\-]{0,128}")) { throw new IllegalArgumentException("Invalid default zone"); }
        this.defaultZone = defaultZone;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/favicon.ico".equals(path)) { exchange.sendResponseHeaders(204, -1); return; }
            if ("GET".equals(method) && "/health".equals(path)) { respond(exchange, 200, Collections.singletonMap("status", "UP")); return; }
            if ("GET".equals(method) && "/api/defaults".equals(path)) { respond(exchange, 200, Collections.singletonMap("zone", defaultZone)); return; }
            if ("GET".equals(method) && Arrays.asList("/", "/app.js", "/style.css").contains(path)) {
                String name = "/".equals(path) ? "index.html" : path.substring(1);
                try (InputStream in = getClass().getResourceAsStream("/web/" + name)) {
                    if (in == null) { throw new IllegalStateException("Missing page resource"); }
                    send(exchange, 200, name.endsWith("html") ? "text/html; charset=utf-8" : name.endsWith("js") ? "application/javascript; charset=utf-8" : "text/css; charset=utf-8", PlatformClient.readLimited(in));
                }
                return;
            }
            String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
            if (requestOrigin != null && !requestOrigin.equals(origin)) { respond(exchange, 403, Collections.singletonMap("error", "Same-origin request required")); return; }
            if ("POST".equals(method)) {
                String content = exchange.getRequestHeaders().getFirst("Content-Type");
                if (content == null || !content.startsWith("application/json")) { throw new IllegalArgumentException("JSON request required"); }
                JsonNode body = json.readTree(PlatformClient.readLimited(exchange.getRequestBody()));
                if (body == null || !body.isObject()) { throw new IllegalArgumentException("JSON object required"); }
                if ("/api/login".equals(path)) {
                    session.authenticate(exchange.getRequestHeaders().getFirst("Authorization"), body.path("zone").asText(""));
                    ObjectNode reply = session.state(); reply.put("sessionId", sessionId); respond(exchange, 200, reply); return;
                }
                if (!sessionId.equals(exchange.getRequestHeaders().getFirst("X-Debug-Session"))) { respond(exchange, 401, Collections.singletonMap("error", "Connect with a valid platform login first")); return; }
                if (!path.matches("/api/[a-z-]+")) { respond(exchange, 404, Collections.singletonMap("error", "Unknown route")); return; }
                respond(exchange, 200, session.action(path.substring(5), body)); return;
            }
            if ("GET".equals(method) && ("/api/state".equals(path) || "/api/ai-status".equals(path))) {
                if (!sessionId.equals(exchange.getRequestHeaders().getFirst("X-Debug-Session"))) { respond(exchange, 401, Collections.singletonMap("error", "Connect first")); return; }
                respond(exchange, 200, "/api/ai-status".equals(path) ? session.aiStatus() : session.state()); return;
            }
            respond(exchange, 404, Collections.singletonMap("error", "Unknown route"));
        } catch (PlatformClient.AuthenticationRejectedException e) {
            ObjectNode error = json.createObjectNode(); error.put("error", e.getMessage()); error.put("code", "PLATFORM_AUTH_REJECTED");
            respond(exchange, 409, error);
        } catch (IllegalArgumentException e) { respond(exchange, 400, Collections.singletonMap("error", e.getMessage())); }
        catch (IllegalStateException e) { respond(exchange, 409, Collections.singletonMap("error", e.getMessage())); }
        catch (Exception e) { respond(exchange, 500, Collections.singletonMap("error", "Request failed; check connectivity and request format")); }
        finally { exchange.close(); }
    }
    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.writeValueAsBytes(value));
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); session.close(); workers.shutdownNow(); }
}
