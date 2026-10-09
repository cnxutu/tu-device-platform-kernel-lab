package com.tu.deviceplatform.tofumsclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofums.adapter.TofuMsSimulator;
import com.tu.deviceplatform.tofumsclient.adapter.TofuMsControlClient;
import com.tu.deviceplatform.tofumsclient.core.TimedOpticalControl;
import com.xmkj.codec.adapter.uav.device.tofums.TofuMsCustomProtocolAdapter;
import com.xmkj.codec.adapter.uav.device.tofums.command.TofuMsCommandRegistry;
import com.xmkj.codec.adapter.uav.device.tofums.mapper.TofuMsUpstreamMapper;
import com.xmkj.sdk.iot.codec.adapter.upstream.DeviceInfo;
import com.xmkj.sdk.iot.common.biz.dto.IotDeviceConnectionDTO;
import com.xmkj.sdk.iot.common.mq.message.IotDeviceMessage;
import com.xmkj.sdk.iot.protocol.adapter.device.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class TofuMsCommandClientTest {
    @Test void timedPtzUsesProductionEncodingAndShowsRealFramesAndSeparateAck() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        try (TofuMsSimulator sim = new TofuMsSimulator(0);
             TofuMsControlClient client = new TofuMsControlClient("127.0.0.1", sim.getPort(), 1, log::add);
             TimedOpticalControl control = new TimedOpticalControl(client, log::add)) {
            await(client::isOnline, 3000);
            control.move("ptz_control", 2, 30, 350).get(2, TimeUnit.SECONDS);
            TofuMsSimulator.Receipt move = sim.awaitReceipt(1000), stop = sim.awaitReceipt(2000);
            assertNotNull(move); assertNotNull(stop);
            assertEquals("FF010002130016", serial(move)); assertEquals("FF010000000001", serial(stop));
            assertTrue(stop.receivedNanos - move.receivedNanos >= TimeUnit.MILLISECONDS.toNanos(300));
            await(() -> contains(log, "RX_FRAME hex=EC9112000000020000") && contains(log, "RX_FRAME hex=EC911100000000")
                    && contains(log, "TX_FRAME hex=EC9103") && contains(log, "thing.property.post"), 2000);
            assertTrue(contains(log, "TCP write only; physical execution unconfirmed"));
            assertTrue(contains(log, "optical_protocol_feedback"));
            assertNull(sim.awaitReceipt(200));
        }
    }

    @Test void manualStopCancelsTimerAndQuitStopsActiveFocus() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        try (TofuMsSimulator sim = new TofuMsSimulator(0);
             TofuMsControlClient client = new TofuMsControlClient("127.0.0.1", sim.getPort(), 1, log::add);
             TimedOpticalControl control = new TimedOpticalControl(client, log::add)) {
            await(client::isOnline, 3000);
            control.move("zoom_control", 1, 50, 600).get(2, TimeUnit.SECONDS);
            assertEquals("8101040723FF", serial(sim.awaitReceipt(1000)));
            assertThrows(IllegalStateException.class, () -> control.move("ptz_control", 1, 30, 300));
            control.stop("zoom_control").get(2, TimeUnit.SECONDS);
            assertEquals("8101040700FF", serial(sim.awaitReceipt(1000)));
            assertNull(sim.awaitReceipt(750));
            control.move("optical_focus_control", 1, null, 1000).get(2, TimeUnit.SECONDS);
            assertEquals("8101040802FF", serial(sim.awaitReceipt(1000)));
            control.close();
            assertEquals("8101040800FF", serial(sim.awaitReceipt(1000)));
            assertNull(sim.awaitReceipt(1100));
            assertThrows(IllegalStateException.class, () -> control.move("ptz_control", 1, 30, 300));
        }
    }

    @Test void invalidOrOfflineMovementIsRejectedAndNeverSentAfterOnline() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        try (TofuMsSimulator sim = new TofuMsSimulator(0)) {
            sim.setHeartbeatReplies(false);
            try (TofuMsControlClient client = new TofuMsControlClient("127.0.0.1", sim.getPort(), 1, log::add);
                 TimedOpticalControl control = new TimedOpticalControl(client, log::add)) {
                assertThrows(IllegalArgumentException.class, () -> control.move("ptz_control", 2, 30, 0));
                assertThrows(IllegalArgumentException.class, () -> control.move("ptz_control", 2, 101, 200));
                assertThrows(IllegalArgumentException.class, () -> control.move("optical_focus_control", 1, 30, 200));
                assertThrows(IllegalStateException.class, () -> control.move("ptz_control", 2, 30, 200));
                sim.setHeartbeatReplies(true); await(client::isOnline, 3000);
                assertNull(sim.awaitReceipt(400));
                control.stop(null).get(3, TimeUnit.SECONDS);
                assertEquals("FF010000000001", serial(sim.awaitReceipt(1000)));
                assertEquals("8101040700FF", serial(sim.awaitReceipt(1000)));
                assertEquals("8101040800FF", serial(sim.awaitReceipt(1000)));
            }
        }
    }

    @Test void failedStopAfterDisconnectIsVisible() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        TofuMsSimulator sim = new TofuMsSimulator(0);
        try (TofuMsControlClient client = new TofuMsControlClient("127.0.0.1", sim.getPort(), 1, log::add);
             TimedOpticalControl control = new TimedOpticalControl(client, log::add)) {
            await(client::isOnline, 3000);
            control.move("ptz_control", 2, 30, 300).get(2, TimeUnit.SECONDS);
            assertNotNull(sim.awaitReceipt(1000));
            sim.close(); await(() -> !client.isOnline(), 2000);
            await(() -> contains(log, "STOP_FAILED"), 2000);
            assertTrue(contains(log, "physical motion may continue"));
        } finally { sim.close(); }
    }

    @Test void reconnectNeverReplaysMovementAndRequiresExplicitStopAfterUnconfirmedStop() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        try (TofuMsSimulator sim = new TofuMsSimulator(0);
             TofuMsControlClient client = new TofuMsControlClient("127.0.0.1", sim.getPort(), 1, log::add);
             TimedOpticalControl control = new TimedOpticalControl(client, log::add)) {
            await(client::isOnline, 3000);
            control.move("ptz_control", 2, 30, 300).get(2, TimeUnit.SECONDS);
            assertNotNull(sim.awaitReceipt(1000)); sim.disconnectClients();
            await(() -> !client.isOnline(), 1000);
            await(() -> contains(log, "STOP_FAILED"), 1000);
            await(client::isOnline, 3000);
            assertNull(sim.awaitReceipt(400));
            assertThrows(IllegalStateException.class, () -> control.move("ptz_control", 1, 30, 300));
            control.stop("ptz_control").get(2, TimeUnit.SECONDS);
            assertEquals("FF010000000001", serial(sim.awaitReceipt(1000)));
        }
    }

    @Test void diagnosticObserverCannotChangeFramesOrBreakControl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Boolean> directions = new CopyOnWriteArrayList<>();
        try (TofuMsSimulator sim = new TofuMsSimulator(0);
             TofuMsCustomProtocolAdapter adapter = new TofuMsCustomProtocolAdapter(mapper, new TofuMsCommandRegistry(mapper), new TofuMsUpstreamMapper(mapper))) {
            adapter.setTrafficObserver((id, outbound, frame) -> {
                directions.add(outbound); Arrays.fill(frame, (byte) 0);
                throw new IllegalStateException("diagnostic failure");
            });
            adapter.init(Collections.emptyMap());
            adapter.syncDeviceConnection(new DeviceConnectionChange().setType(DeviceConnectionChangeType.ADDED).setAfter(
                    new IotDeviceConnectionDTO().setDeviceId(7001L).setProductKey("lab-optical").setDeviceName("lab-client")
                            .setIp("127.0.0.1").setConfig("{\"port\":" + sim.getPort() + "}")));
            await(() -> adapter.getConnectionCount() == 1, 3000);
            Map<String, Object> envelope = new LinkedHashMap<>(); envelope.put("identifier", "ptz_control");
            envelope.put("params", Collections.singletonMap("action", 0));
            adapter.downstream(new DeviceInfo().setProductKey("lab-optical").setDeviceName("lab-client"),
                    new IotDeviceMessage().setDeviceId(7001L).setRequestId("lab-stop").setMethod("thing.service.invoke").setParams(envelope));
            assertEquals("FF010000000001", serial(sim.awaitReceipt(1000)));
            await(() -> directions.contains(true) && directions.contains(false), 1000);
            assertEquals(1, adapter.getConnectionCount());
        }
    }

    private static String serial(TofuMsSimulator.Receipt receipt) {
        assertNotNull(receipt); return receipt.payload.path("SerialData").path("Data").asText();
    }
    private static boolean contains(List<String> log, String text) { return log.stream().anyMatch(line -> line.contains(text)); }
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(10); }
        assertTrue(condition.getAsBoolean(), "Condition timed out");
    }
}
