package com.tu.deviceplatform.hikvisiongeo.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tu.deviceplatform.hikvisiongeo.core.ServiceInvokeResult;
import com.tu.deviceplatform.hikvisiongeo.core.ConfigurableServiceInvoker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** P2 RPC client. It only sends when the user explicitly invokes a local testbench action. */
public final class P2ServiceClient implements ConfigurableServiceInvoker {
    public static final String RPC_PATH = "/rpc-api/iot/devices/service/invoke";
    private static final int MAX_RESPONSE_BYTES = 65536;
    private final ObjectMapper mapper;
    private final String authorization;
    private volatile URI endpoint;
    private volatile String baseUrl = "";

    public P2ServiceClient(ObjectMapper mapper, String authorization) {
        this.mapper = mapper;
        this.authorization = authorization == null ? "" : authorization.trim();
    }

    public P2ServiceClient(ObjectMapper mapper, String baseUrl, String authorization) {
        this(mapper, authorization);
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            configureBaseUrl(baseUrl);
        }
    }

    @Override
    public synchronized void configureBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            endpoint = null;
            baseUrl = "";
            return;
        }
        String normalized = value.trim().replaceAll("/+$", "");
        URI base;
        try {
            base = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("P2 base URL is invalid", e);
        }
        String scheme = base.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("P2 base URL must be an HTTP(S) origin or context path without credentials, query, or fragment");
        }
        endpoint = URI.create(normalized + RPC_PATH);
        baseUrl = normalized;
    }

    @Override
    public ServiceInvokeResult invoke(ObjectNode request) {
        URI target = endpoint;
        if (target == null) {
            throw new IllegalStateException("Configure the P2 base URL before sending");
        }
        String requestId = "geo-test-" + UUID.randomUUID().toString();
        HttpURLConnection connection = null;
        try {
            URL url = target.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            int timeoutSeconds = request.path("timeoutSeconds").asInt(30);
            connection.setReadTimeout(Math.min(305000, Math.max(5, timeoutSeconds + 5) * 1000));
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Request-Id", requestId);
            if (!authorization.isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }
            byte[] body = mapper.writeValueAsBytes(request);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = response == null ? "" : new String(readLimited(response, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            boolean accepted = status >= 200 && status < 300 && commonResultAccepted(responseBody);
            return new ServiceInvokeResult(status, accepted, requestId, responseBody, null);
        } catch (IOException e) {
            return new ServiceInvokeResult(-1, false, requestId, "", e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean commonResultAccepted(String body) {
        try {
            JsonNode response = mapper.readTree(body);
            if (response == null || !response.isObject()) {
                return false;
            }
            JsonNode code = response.get("code");
            if (code != null && code.isNumber()) {
                return code.asInt() == 0;
            }
            JsonNode success = response.get("success");
            return success != null && success.isBoolean() && success.asBoolean();
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = closeable.read(buffer)) != -1) {
                if (output.size() + length > limit) {
                    throw new IOException("P2 response exceeds size limit");
                }
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    @Override public boolean isConfigured() { return endpoint != null; }
    @Override public String getBaseUrl() { return baseUrl; }
    @Override public boolean hasAuthorization() { return !authorization.isEmpty(); }
}
