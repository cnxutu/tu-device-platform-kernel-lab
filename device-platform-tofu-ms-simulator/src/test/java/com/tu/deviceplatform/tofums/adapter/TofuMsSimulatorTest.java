package com.tu.deviceplatform.tofums.adapter;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TofuMsSimulatorTest {
    private static final String STOP = "{\"ControlType\":\"SerialControl\",\"SerialType\":\"PELCO_D\",\"SerialData\":{\"Lens\":7,\"Data\":\"FF010000000001\"}}";
    @Test void acceptsFragmentedAndCoalescedFramesAndRejectsInvalidChecksum() throws Exception {
        try (TofuMsSimulator sim = new TofuMsSimulator(0); Socket socket = new Socket("127.0.0.1", sim.getPort())) {
            socket.setSoTimeout(2000);
            byte[] command = frame(3, STOP.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(command, 0, 4); socket.getOutputStream().flush();
            socket.getOutputStream().write(command, 4, command.length - 4);
            socket.getOutputStream().write(frame(17, new byte[0])); socket.getOutputStream().write(command); socket.getOutputStream().flush();
            assertNotNull(sim.awaitReceipt(2000)); assertNotNull(sim.awaitReceipt(2000));
            assertEquals(0, readAck(socket));
            socket.getOutputStream().write(frame(3, STOP.replace("000001", "000002").getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, readAck(socket)); assertEquals(2, readAck(socket));
            assertNull(sim.awaitReceipt(100)); assertEquals(1, sim.getRejectedFrames());
        }
    }
    @Test void rejectsOversizedFrameAndClosesClientsOnShutdown() throws Exception {
        TofuMsSimulator sim = new TofuMsSimulator(0);
        try (Socket socket = new Socket("127.0.0.1", sim.getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(new byte[] {(byte) 236, (byte) 145, 3, 127, -1, -1, -1});
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(sim.getRejectedFrames() > 0);
        } finally { sim.close(); }
        assertEquals(0, sim.getClientCount());
    }
    private static int readAck(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        while (true) {
            assertEquals(236, in.readUnsignedByte()); assertEquals(145, in.readUnsignedByte());
            int type = in.readUnsignedByte(); int length = in.readInt(); byte[] body = new byte[length]; in.readFully(body);
            if (type == 18) { return body[1] & 255; }
        }
    }
    private static byte[] frame(int type, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {(byte) 236, (byte) 145, (byte) type}); out.writeInt(body.length); out.write(body); return bytes.toByteArray();
    }
}
