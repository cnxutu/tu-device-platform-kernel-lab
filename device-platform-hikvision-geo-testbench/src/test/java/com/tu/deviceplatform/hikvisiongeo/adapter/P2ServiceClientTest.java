package com.tu.deviceplatform.hikvisiongeo.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class P2ServiceClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsTheImportedRequestToTheFixedP2RpcRoute() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc-api/iot/devices/service/invoke", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(read(exchange.getRequestBody()), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0,\"data\":{\"requestId\":\"simulated-response\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        P2ServiceClient client = new P2ServiceClient(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), null);
        ObjectNode request = mapper.createObjectNode();
        request.put("deviceId", 36);
        request.put("identifier", "target_acquire");
        request.putObject("params").put("targetId", "SIM-TARGET-001");
        request.put("syncMode", "BLOCK");

        com.tu.deviceplatform.hikvisiongeo.core.ServiceInvokeResult result = client.invoke(request);

        assertTrue(result.isAccepted());
        assertEquals(200, result.getHttpStatus());
        assertEquals(P2ServiceClient.RPC_PATH, path.get());
        assertEquals(request, mapper.readTree(body.get()));
        assertTrue(result.getRequestId().startsWith("geo-test-"));
    }

    @Test
    void rejectsUnexpectedP2BaseUrlFormsAndDoesNotTreatBusinessErrorAsAccepted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        P2ServiceClient unconfigured = new P2ServiceClient(mapper, (String) null, null);
        assertThrows(IllegalArgumentException.class, () -> unconfigured.configureBaseUrl("file:///tmp/p2"));
        assertThrows(IllegalArgumentException.class, () -> unconfigured.configureBaseUrl("http://user@127.0.0.1"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(P2ServiceClient.RPC_PATH, exchange -> {
            byte[] response = "{\"code\":40001,\"msg\":\"rejected\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        unconfigured.configureBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ObjectNode request = mapper.createObjectNode().put("identifier", "target_follow");

        com.tu.deviceplatform.hikvisiongeo.core.ServiceInvokeResult result = unconfigured.invoke(request);

        assertFalse(result.isAccepted());
        assertEquals(200, result.getHttpStatus());
    }

    private byte[] read(java.io.InputStream input) throws IOException {
        try (java.io.InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
            return out.toByteArray();
        }
    }
}
