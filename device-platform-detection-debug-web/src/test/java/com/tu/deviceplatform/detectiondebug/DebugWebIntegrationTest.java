package com.tu.deviceplatform.detectiondebug;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tu.deviceplatform.detectiondebug.adapter.*;
import com.tu.deviceplatform.detectiondebug.core.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP boundary and scheduling with a fake platform; no real network devices are used. */
class DebugWebIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH = "Bearer synthetic-test-login";

    @Test void exactCoordinatesSeparateHeightDatumsAndFreshTimestamps() throws Exception {
        ObjectNode point = point(120.1234567, 30.1234567, 135.75); point.put("relativeHeight", 27.5);
        JsonNode first = NtXr1Payload.locate("SIM-DETECT-01", "SIM-UAV-01", point, 100000L);
        JsonNode drone = first.path("dronePos").get(0);
        assertEquals(135.75, drone.path("uavAltitude").asDouble());
        assertEquals(27.5, drone.path("relativeHeight").asDouble());
        assertEquals(120.1234567, drone.path("uavLongitude").asDouble());
        assertEquals(0, drone.path("coordinateTypeCode").asInt());
        assertFalse(drone.has("geodeticAltitude")); assertFalse(drone.has("aglHeight"));
        assertEquals(100L, first.path("createTime").asLong()); assertEquals(100000L, drone.path("timestamp").asLong());
        point.remove("relativeHeight");
        assertFalse(NtXr1Payload.locate("SIM-DETECT-01", "SIM-UAV-01", point, 200000L).path("dronePos").get(0).has("relativeHeight"));
        assertThrows(IllegalArgumentException.class, () -> NtXr1Payload.locate("bad/#", "uav", point, 1));
        point.put("latitude", 91); assertThrows(IllegalArgumentException.class, () -> NtXr1Payload.point(point));
        point.put("latitude", 0); point.remove("altitude"); assertThrows(IllegalArgumentException.class, () -> NtXr1Payload.point(point));
    }

    @Test void authenticatedRoutesUseActualBusinessContractsAndAutomaticStop() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("SIM-ZONE", f.http("/api/defaults", null, null, null).body.path("zone").asText());
            assertEquals(401, f.http("/api/move", "{}", null, null).status);
            assertEquals(403, f.foreignOriginStatus());
            f.login();
            String move = "{\"identifier\":\"ptz_control\",\"action\":2,\"speed\":15,\"durationMillis\":150}";
            assertEquals(200, f.http("/api/move", move, f.id, null).status);
            f.waitFor(() -> f.calls.size() >= 2);
            assertEquals("/admin-api/b-detect/ptz/move", f.calls.get(0).path);
            assertEquals(7001L, f.calls.get(0).body.path("deviceId").asLong());
            assertEquals(2, f.calls.get(0).body.path("direction").asInt());
            assertEquals("1", f.calls.get(0).body.path("deviceChannelCode").asText());
            assertEquals(0, f.calls.get(1).body.path("direction").asInt());
            assertTrue(f.calls.get(1).time - f.calls.get(0).time >= 140);
            assertEquals(200, f.http("/api/ai-start", "{\"captureTimeoutSeconds\":30,\"sessionTimeoutSeconds\":120}", f.id, null).status);
            Call ai = f.calls.get(2); assertTrue(ai.path.endsWith("ai-tracking/start"));
            assertFalse(ai.body.has("azimuth")); assertFalse(ai.body.has("elevation"));
            assertEquals(409, f.http("/api/move", move, f.id, null).status);
            assertEquals(409, f.http("/api/point-once", f.single(), f.id, null).status);
            assertEquals(200, f.http("/api/ai-stop", "{}", f.id, null).status);
            Call stop = f.calls.get(3); assertEquals(ai.body.path("trackingId"), stop.body.path("trackingId"));
            assertFalse(stop.body.has("returnToStandby")); assertTrue(stop.path.endsWith("ai-tracking/stop"));
            assertTrue(f.published.isEmpty());
            JsonNode state = f.http("/api/state", null, f.id, null).body;
            assertFalse(state.has("aiTaskId")); assertTrue(state.path("aiFeedbackAvailable").asBoolean());
            assertFalse(state.toString().contains(AUTH)); assertFalse(state.toString().contains("deviceSecret"));
        }
    }

    @Test void uncertainAiStartPreservesOwnerUntilExplicitSuccessfulStop() throws Exception {
        try (Fixture f = new Fixture()) {
            f.login(); f.rejectCommands = true;
            assertEquals(409, f.http("/api/ai-start", "{\"captureTimeoutSeconds\":10,\"sessionTimeoutSeconds\":20}", f.id, null).status);
            assertTrue(f.http("/api/state", null, f.id, null).body.has("aiTaskId"));
            assertEquals(409, f.http("/api/ai-stop", "{}", f.id, null).status);
            assertTrue(f.http("/api/state", null, f.id, null).body.has("aiTaskId"));
            f.rejectCommands = false;
            assertEquals(200, f.http("/api/ai-stop", "{}", f.id, null).status);
            assertFalse(f.http("/api/state", null, f.id, null).body.has("aiTaskId"));
        }
    }

    @Test void repeatedPointRequestsRetainTokenAndFailedReplacementPreservesSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.login(); String originalId=f.id;
            assertEquals(200,f.http("/api/point-once",f.single(),f.id,null).status);
            assertEquals(200,f.http("/api/point-once",f.single(),f.id,null).status);
            assertEquals("STORED",f.http("/api/state",null,f.id,null).body.path("platformAuthentication").asText());
            assertEquals(400,f.httpWithAuthorization("/api/login","{}",null,null,"Bearer ").status);
            Response rejected=f.httpWithAuthorization("/api/login","{}",null,null,"Bearer synthetic-wrong-token");
            assertEquals(409,rejected.status);assertEquals("PLATFORM_AUTH_REJECTED",rejected.body.path("code").asText());
            assertEquals(200,f.http("/api/scene-check","{}",originalId,null).status);
            JsonNode state=f.http("/api/state",null,originalId,null).body;
            assertEquals("STORED",state.path("platformAuthentication").asText());
            assertFalse(state.toString().contains(AUTH));assertFalse(state.toString().contains("synthetic-wrong-token"));
            assertFalse(state.toString().contains("deviceSecret"));
            assertTrue(f.calls.isEmpty()); // Point publication and auth checks never call camera controls.
        }
    }

    @Test void platformRejectionIsDistinctFromLocalSessionExpiryAndCanBeRenewed() throws Exception {
        for(boolean http401:Arrays.asList(false,true)) {
            try (Fixture f = new Fixture()) {
                f.login();f.acceptedAuthorization="Bearer synthetic-rotated-token";f.unauthorizedHttp=http401;
                Response denied=f.http("/api/scene-check","{}",f.id,null);
                assertEquals(409,denied.status);assertEquals("PLATFORM_AUTH_REJECTED",denied.body.path("code").asText());
                assertFalse(denied.body.path("error").asText().contains("expired"));
                assertEquals("REJECTED",f.http("/api/state",null,f.id,null).body.path("platformAuthentication").asText());
                assertEquals(401,f.http("/api/state",null,"stale-local-session",null).status);
                assertEquals(200,f.httpWithAuthorization("/api/login","{\"zone\":\"SIM-ZONE\"}",null,null,f.acceptedAuthorization).status);
                JsonNode state=f.http("/api/state",null,f.id,null).body;
                assertEquals("STORED",state.path("platformAuthentication").asText());assertEquals("SIM-ZONE",state.path("zone").asText());
                assertTrue(f.calls.isEmpty());assertTrue(f.published.isEmpty());
            }
        }
    }

    @Test void localServiceRestartInvalidatesOnlyItsPreviousSessionCapability() throws Exception {
        String previousId;
        try(Fixture previous=new Fixture()){previous.login();previousId=previous.id;}
        try(Fixture next=new Fixture()){
            assertEquals(401,next.http("/api/state",null,previousId,null).status);
            next.login();assertNotEquals(previousId,next.id);
            assertEquals("STORED",next.http("/api/state",null,next.id,null).body.path("platformAuthentication").asText());
        }
    }

    @Test void feedbackUsesExactTaskAndPreservesHistoricalEventTime() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(401, f.http("/api/ai-status", null, null, null).status);
            f.login();
            assertEquals(409, f.http("/api/ai-status", null, f.id, null).status);
            f.http("/api/ai-start", "{\"captureTimeoutSeconds\":30,\"sessionTimeoutSeconds\":120}", f.id, null);
            String task = f.calls.get(0).body.path("trackingId").asText();
            f.feedback = JSON.createObjectNode(); f.feedback.put("deviceId",7001); f.feedback.put("trackingId",task);
            f.feedback.put("state","LOCKED");
            long now=System.currentTimeMillis(); f.feedback.put("feedbackTime",now); f.feedback.put("serverTime",now); f.feedback.put("feedbackAgeMillis",0);
            JsonNode locked=f.http("/api/ai-status",null,f.id,null).body;
            assertEquals("LOCKED",locked.path("state").asText()); assertFalse(locked.has("locked"));
            assertEquals(1,f.calls.size()); // State query never invokes a control or publishes a point.
            f.feedback.put("trackingId","manual-wrong-task");
            assertEquals(409,f.http("/api/ai-status",null,f.id,null).status);
            f.feedback.put("trackingId",task); f.feedback.put("feedbackTime",now+10000);
            assertEquals(409,f.http("/api/ai-status",null,f.id,null).status);
            f.feedback.put("feedbackTime",now-6000); f.feedback.put("feedbackAgeMillis",6000);
            JsonNode history=f.http("/api/ai-status",null,f.id,null).body;
            assertEquals("LOCKED",history.path("state").asText()); assertEquals(now-6000,history.path("feedbackTime").asLong());
            assertFalse(history.has("locked"));
            f.feedback.put("state","LOST");
            assertEquals("LOST",f.http("/api/ai-status",null,f.id,null).body.path("state").asText());
            f.feedback=null;
            assertEquals(409,f.http("/api/ai-status",null,f.id,null).status);
            assertEquals(200,f.http("/api/ai-stop","{}",f.id,null).status);
            assertFalse(f.http("/api/state",null,f.id,null).body.has("aiTaskId"));
            assertEquals(task,f.http("/api/state",null,f.id,null).body.path("lastAiTaskId").asText());
        }
    }

    @Test void previewDoesNotPublishAndSequenceUsesStableTargetAndUpdatedExactPoint() throws Exception {
        try (Fixture f = new Fixture()) {
            f.login(); assertEquals(200, f.http("/api/preview", f.single(), f.id, null).status);
            assertTrue(f.published.isEmpty());
            String start = "{\"targetId\":\"SIM-UAV-01\",\"intervalMillis\":1000,\"durationSeconds\":3,\"points\":["+point(120,30,100)+","+point(121,31,110)+"]}";
            assertEquals(200, f.http("/api/points-start", start, f.id, null).status);
            f.waitFor(() -> f.published.size() >= 2);
            assertTrue(f.topics.get(0).endsWith("deviceInfo/SIM-DETECT-01"));
            assertTrue(f.topics.get(1).endsWith("LocatedResult/SIM-DETECT-01"));
            assertEquals(409, f.http("/api/points-start", start, f.id, null).status);
            f.waitFor(() -> f.published.size() >= 3);
            assertEquals(121, f.published.get(2).path("dronePos").get(0).path("uavLongitude").asInt());
            assertEquals(200, f.http("/api/point-update", "{\"point\":"+point(122,32,125)+"}", f.id, null).status);
            f.waitFor(() -> f.published.size() >= 4);
            JsonNode updated=f.published.get(3).path("dronePos").get(0);
            assertEquals("SIM-UAV-01",updated.path("trackId").asText()); assertEquals(125,updated.path("uavAltitude").asInt());
            assertEquals(200, f.http("/api/points-stop", "{}", f.id, null).status);
            int count = f.published.size(); Thread.sleep(1100); assertEquals(count,f.published.size());
            assertTrue(f.calls.isEmpty()); // Never bypass the protocol by calling a business point-injection API.
        }
    }

    @Test void senderExpiresWithoutBrowserAndRejectsInvalidInputBeforeSideEffects() throws Exception {
        try (Fixture f = new Fixture()) {
            f.login();
            assertEquals(400,f.http("/api/point-once","{\"targetId\":\"uav\",\"point\":{\"longitude\":120,\"latitude\":30}}",f.id,null).status);
            assertEquals(400,f.http("/api/points-start","{\"targetId\":\"uav\",\"intervalMillis\":0,\"durationSeconds\":1,\"points\":[]}",f.id,null).status);
            assertEquals(400,f.http("/api/move","{\"identifier\":\"ptz_control\",\"action\":2,\"speed\":15,\"durationMillis\":10001}",f.id,null).status);
            assertTrue(f.published.isEmpty()); assertTrue(f.calls.isEmpty());
            assertEquals(200,f.http("/api/points-start","{\"targetId\":\"uav\",\"intervalMillis\":1000,\"durationSeconds\":1,\"points\":["+point(120,30,90)+"]}",f.id,null).status);
            f.waitFor(() -> f.published.size()>=2); Thread.sleep(1300);
            assertFalse(f.http("/api/state",null,f.id,null).body.path("sourceRunning").asBoolean());
            assertEquals(2,f.published.size());
        }
    }

    @Test void scenePreviewApplyAndRestorePreserveUnknownFieldsAndAbsentValues() throws Exception {
        try (Fixture f = new Fixture()) {
            f.login();
            assertEquals(200,f.http("/api/scene-select","{\"scene\":\"pointing\"}",f.id,null).status);
            assertTrue(f.calls.isEmpty()); assertTrue(f.published.isEmpty());
            Response checked=f.http("/api/scene-check","{}",f.id,null);
            assertTrue(checked.body.path("scene").path("preflight").toString().contains("deviceMode"));
            assertEquals(400,f.http("/api/scene-preview","{\"minElevation\":0,\"maxElevation\":5}",f.id,null).status);
            assertEquals(200,f.http("/api/scene-preview",window(),f.id,null).status);
            assertFalse(f.config.path("trackingEnabled").asBoolean());
            assertEquals(200,f.http("/api/scene-apply","{}",f.id,null).status);
            assertTrue(f.config.path("trackingEnabled").asBoolean()); assertEquals("pointing",f.config.path("guidanceMode").asText());
            assertEquals(12,f.config.path("trackingNorthOffset").asInt()); assertEquals("untouched",f.config.path("vendorSetting").asText());
            assertEquals(409,f.http("/api/scene-select","{\"scene\":\"manual\"}",f.id,null).status);
            f.config.put("vendorSetting","concurrent unrelated change");
            f.owner="business-owner";
            assertEquals(409,f.http("/api/scene-finish","{}",f.id,null).status);
            assertTrue(f.config.path("trackingEnabled").asBoolean());
            f.owner=null;
            assertEquals(200,f.http("/api/scene-finish","{}",f.id,null).status);
            assertFalse(f.config.path("trackingEnabled").asBoolean());
            assertFalse(f.config.has("guidanceMode")); assertFalse(f.config.has("trackingMinElevation"));
            assertEquals("concurrent unrelated change",f.config.path("vendorSetting").asText());
            assertFalse(f.http("/api/state",null,f.id,null).body.toString().contains("synthetic-secret"));
            assertTrue(f.published.isEmpty());
        }
    }

    @Test void configConflictAndUncertainUpdateRetainRecoveryAndNeverOverwriteOtherOperator() throws Exception {
        try (Fixture f=new Fixture()) {
            f.login(); f.http("/api/scene-select","{\"scene\":\"pointing\"}",f.id,null);
            f.http("/api/scene-preview",window(),f.id,null);
            f.config.put("guidanceMode","tracking");
            assertEquals(409,f.http("/api/scene-apply","{}",f.id,null).status);assertTrue(f.calls.isEmpty());
            f.config.remove("guidanceMode"); f.http("/api/scene-preview",window(),f.id,null);
            f.rejectUpdateAfterSave=true;
            assertEquals(409,f.http("/api/scene-apply","{}",f.id,null).status);
            assertTrue(f.http("/api/state",null,f.id,null).body.path("scene").path("pendingRestore").asBoolean());
            f.rejectUpdateAfterSave=false;f.config.put("trackingMinElevation",-5);
            assertEquals(409,f.http("/api/scene-finish","{}",f.id,null).status);
            assertEquals(-5,f.config.path("trackingMinElevation").asInt());
            f.config.put("trackingMinElevation",0);
            assertEquals(200,f.http("/api/scene-finish","{}",f.id,null).status);
            assertFalse(f.config.has("trackingMinElevation"));
        }
    }

    @Test void coordinateSceneBlocksAtGlobalSwitchAndUnsupportedFullTrackingBeforePublishing() throws Exception {
        try (Fixture f=new Fixture()) {
            f.login(); f.global=true;
            assertEquals(409,f.http("/api/point-once",f.single(),f.id,null).status); // Upstream-only cannot silently cause movement.
            f.http("/api/scene-select","{\"scene\":\"pointing\"}",f.id,null);
            assertEquals(409,f.http("/api/point-once",f.single(),f.id,null).status);
            f.http("/api/scene-preview",window(),f.id,null); f.http("/api/scene-apply","{}",f.id,null);
            f.global=false;
            assertEquals(409,f.http("/api/point-once",f.single(),f.id,null).status);assertTrue(f.published.isEmpty());
            f.global=true;
            assertEquals(200,f.http("/api/point-once",f.single(),f.id,null).status);assertEquals(2,f.published.size());
            f.owner="active-auto-task";
            assertEquals(409,f.http("/api/scene-finish","{}",f.id,null).status);
            assertTrue(f.calls.stream().noneMatch(c->c.path.endsWith("tracking/stop")));
            f.owner=null; f.http("/api/scene-finish","{}",f.id,null);
            f.http("/api/scene-select","{\"scene\":\"tracking\"}",f.id,null);
            assertEquals(409,f.http("/api/point-once",f.single(),f.id,null).status);
        }
    }

    private static String window(){return "{\"minElevation\":0,\"maxElevation\":5,\"windowConfirmed\":true}";}

    @Test void restorePreservesExplicitNullAndInvalidPreviewCannotApplyAnOldPlan() throws Exception {
        try(Fixture f=new Fixture()) {
            f.config.putNull("trackingMinElevation");f.login();
            f.http("/api/scene-select","{\"scene\":\"pointing\"}",f.id,null);
            f.http("/api/scene-preview",window(),f.id,null);
            assertEquals(400,f.http("/api/scene-preview","{\"minElevation\":5,\"maxElevation\":0,\"windowConfirmed\":true}",f.id,null).status);
            assertEquals(409,f.http("/api/scene-apply","{}",f.id,null).status);assertTrue(f.calls.isEmpty());
            f.http("/api/scene-preview",window(),f.id,null);f.http("/api/scene-apply","{}",f.id,null);
            assertEquals(200,f.http("/api/scene-finish","{}",f.id,null).status);
            assertTrue(f.config.has("trackingMinElevation"));assertTrue(f.config.path("trackingMinElevation").isNull());
        }
    }

    @Test void unavailablePreflightBlocksPublishAndBasicSceneDoesNotRequireGlobalSwitch() throws Exception {
        try(Fixture f=new Fixture()) {
            f.login();f.preflightUnavailable=true;
            assertEquals(409,f.http("/api/point-once",f.single(),f.id,null).status);
            assertTrue(f.published.isEmpty());f.preflightUnavailable=false;
            f.http("/api/scene-select","{\"scene\":\"basic\"}",f.id,null);
            JsonNode checked=f.http("/api/scene-check","{}",f.id,null).body.path("scene").path("preflight").path("checks");
            for(JsonNode item:checked){assertNotEquals("global",item.path("key").asText());assertNotEquals("BLOCK",item.path("status").asText());}
        }
    }

    private static ObjectNode point(double longitude, double latitude, double altitude) {
        ObjectNode value=JSON.createObjectNode();value.put("longitude",longitude);value.put("latitude",latitude);value.put("altitude",altitude);return value;
    }
    private static final class Call {
        final String path; final JsonNode body; final long time = System.currentTimeMillis();
        Call(String path,JsonNode body){this.path=path;this.body=body;}
    }
    private static final class Response {
        final int status; final JsonNode body;
        Response(int status,JsonNode body){this.status=status;this.body=body;}
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer platform=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final List<Call> calls=new CopyOnWriteArrayList<>();
        final List<JsonNode> published=new CopyOnWriteArrayList<>();
        final List<String> topics=new CopyOnWriteArrayList<>();
        final DebugWebServer web;
        volatile boolean rejectCommands;
        volatile String acceptedAuthorization=AUTH;
        volatile boolean unauthorizedHttp;
        volatile ObjectNode feedback;
        volatile ObjectNode config=JSON.createObjectNode().put("trackingEnabled",false).put("trackingNorthOffset",12).put("vendorSetting","untouched");
        volatile String owner;
        volatile boolean global, rejectUpdateAfterSave, preflightUnavailable;
        String id;
        Fixture() throws Exception {
            platform.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath();
                String body;
                boolean authenticated=acceptedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"));
                if(!authenticated) {body="{\"code\":401}";}
                else if(path.endsWith("/device/get")) {
                    ObjectNode device=JSON.createObjectNode().put("state",1).put("serialNumber","SIM-CAMERA-01").put("protocolType","tofu-ms-tjson").put("deviceSecret","synthetic-secret").put("config",config.toString());
                    body=JSON.createObjectNode().put("code",0).set("data",device).toString();
                }
                else if(path.endsWith("/ptz/preflight")) {
                    ObjectNode out=JSON.createObjectNode().put("deviceId",7001).put("serverTime",System.currentTimeMillis());
                    out.put("occupied",owner!=null);
                    if(owner==null)out.putNull("activeTrackingId");else out.put("activeTrackingId",owner);
                    com.fasterxml.jackson.databind.node.ArrayNode checks=out.putArray("checks");
                    for(String key:Arrays.asList("global","whitelist","online","occupancy","installation","source","sourceZone")) {
                        boolean pass=!(key.equals("global")&&!global)&&!(key.equals("occupancy")&&owner!=null);
                        checks.addObject().put("key",key).put("status",pass?"PASS":"BLOCK").put("message",key+" "+(pass?"ready":"blocked"));
                    }
                    body=preflightUnavailable?"{\"code\":404}":JSON.createObjectNode().put("code",0).set("data",out).toString();
                }
                else if(path.endsWith("/device/update")) {
                    assertEquals("PUT",exchange.getRequestMethod());
                    JsonNode request=JSON.readTree(exchange.getRequestBody()); calls.add(new Call(path,request));
                    assertEquals(7001,request.path("id").asInt());assertEquals("SIM-CAMERA-01",request.path("serialNumber").asText());
                    assertFalse(request.has("deviceSecret")); config=(ObjectNode)JSON.readTree(request.path("config").asText());
                    body=rejectUpdateAfterSave?"{\"code\":500}":"{\"code\":0,\"data\":true}";
                }
                else if(path.endsWith("/ai-tracking/status")) {
                    assertTrue(exchange.getRequestURI().getRawQuery().startsWith("deviceId=7001&trackingId=manual-"));
                    body=feedback==null?"{\"code\":404}":"{\"code\":0,\"data\":"+feedback+"}";
                }
                else {calls.add(new Call(path,JSON.readTree(exchange.getRequestBody())));body="{\"code\":0,\"data\":"+(!rejectCommands)+"}";}
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(!authenticated&&unauthorizedHttp?401:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });platform.start();
            PlatformClient api=new PlatformClient("http://127.0.0.1:"+platform.getAddress().getPort()+"/admin-api",7001,"1");
            DebugSession session=new DebugSession(api,"SIM-DETECT-01",(topic,payload)->{topics.add(topic);published.add(payload.deepCopy());});
            web=new DebugWebServer(session,"127.0.0.1",0,"http://debug.test","SIM-ZONE");
        }
        String single(){return "{\"targetId\":\"SIM-UAV-01\",\"point\":"+point(120,30,100)+"}";}
        int foreignOriginStatus() throws Exception {
            // Java 8 HttpURLConnection silently omits Origin; raw HTTP proves the actual server boundary.
            try(Socket socket=new Socket("127.0.0.1",web.port())) {
                socket.setSoTimeout(2000);
                String request="POST /api/login HTTP/1.1\r\nHost: localhost\r\nOrigin: http://other.invalid\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                String line=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                return Integer.parseInt(line.split(" ")[1]);
            }
        }
        void login() throws Exception { Response response=http("/api/login","{}",null,null);assertEquals(200,response.status);id=response.body.path("sessionId").asText(); }
        Response http(String path,String payload,String session,String origin) throws Exception {
            return httpWithAuthorization(path,payload,session,origin,AUTH);
        }
        Response httpWithAuthorization(String path,String payload,String session,String origin,String authorization) throws Exception {
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+web.port()+path).openConnection();
            c.setConnectTimeout(1000);c.setReadTimeout(18000);if(session!=null)c.setRequestProperty("X-Debug-Session",session);
            if(origin!=null)c.setRequestProperty("Origin",origin);
            if(path.equals("/api/login")&&authorization!=null)c.setRequestProperty("Authorization",authorization);
            if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(payload.getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();JsonNode body;try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){body=JSON.readTree(in);}finally{c.disconnect();}return new Response(status,body);
        }
        void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
            long until=System.currentTimeMillis()+6000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<until)Thread.sleep(25);assertTrue(condition.getAsBoolean());
        }
        @Override public void close(){web.close();platform.stop(0);}
    }
}
