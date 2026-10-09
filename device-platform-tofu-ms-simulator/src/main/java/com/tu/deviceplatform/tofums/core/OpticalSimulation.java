package com.tu.deviceplatform.tofums.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Simulated angles/zoom, not a hardware accuracy model. Validates the serial wire contract independently. */
public final class OpticalSimulation {
    private double pan;
    private double tilt;
    private double zoom = 1;
    private double panStep;
    private double tiltStep;
    private double zoomStep;

    public synchronized void apply(JsonNode payload) {
        if (!"SerialControl".equals(payload.path("ControlType").asText())) { throw new IllegalArgumentException("Unsupported ControlType"); }
        JsonNode serial = payload.path("SerialData");
        String hex = serial.path("Data").asText();
        if (!hex.matches("(?i)[0-9a-f]+") || (hex.length() & 1) != 0 || !serial.path("Lens").isIntegralNumber()
                || serial.path("Lens").intValue() != hex.length() / 2) { throw new IllegalArgumentException("Invalid serial length/hex"); }
        int[] bytes = new int[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) { bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); }
        String type = payload.path("SerialType").asText();
        if ("PELCO_D".equals(type)) {
            if (bytes.length != 7 || bytes[0] != 255 || bytes[1] == 0 || bytes[2] != 0
                    || bytes[3] > 30 || (bytes[3] & 1) != 0 || bytes[4] > 63 || bytes[5] > 63
                    || bytes[6] != ((bytes[1] + bytes[2] + bytes[3] + bytes[4] + bytes[5]) & 255)) {
                throw new IllegalArgumentException("Invalid Pelco-D movement/checksum");
            }
            if ((bytes[3] & 6) == 6 || (bytes[3] & 24) == 24) { throw new IllegalArgumentException("Conflicting Pelco-D directions"); }
            panStep = ((bytes[3] & 2) != 0 ? 1 : (bytes[3] & 4) != 0 ? -1 : 0) * bytes[4] / 20.0;
            tiltStep = ((bytes[3] & 8) != 0 ? 1 : (bytes[3] & 16) != 0 ? -1 : 0) * bytes[5] / 20.0;
        } else if ("VISCA".equals(type)) {
            if (bytes.length != 6 || bytes[0] != 129 || bytes[1] != 1 || bytes[2] != 4 || bytes[5] != 255) {
                throw new IllegalArgumentException("Invalid VISCA envelope");
            }
            int value = bytes[4];
            if (bytes[3] == 7) {
                if (value != 0 && (value < 0x20 || value > 0x37 || (value & 15) > 7)) { throw new IllegalArgumentException("Invalid VISCA zoom"); }
                zoomStep = value == 0 ? 0 : (value < 0x30 ? 1 : -1) * ((value & 15) + 1) / 20.0;
            } else if (bytes[3] != 8 || (value != 0 && value != 2 && value != 3)) {
                throw new IllegalArgumentException("Unsupported VISCA command");
            }
        } else { throw new IllegalArgumentException("Unsupported serial port"); }
    }

    public synchronized ObjectNode nextState(ObjectMapper mapper) {
        pan += panStep; tilt = Math.max(-90, Math.min(90, tilt + tiltStep)); zoom = Math.max(1, Math.min(30, zoom + zoomStep));
        ObjectNode state = mapper.createObjectNode(); state.put("ControlType", "ZoomInfo");
        state.put("PTZInfoH", pan); state.put("PTZInfoV", tilt); state.put("ZoomInfo", zoom);
        return state;
    }
    public synchronized void resetMotion() { panStep = 0; tiltStep = 0; zoomStep = 0; }
}
