package com.tu.deviceplatform.projectionreplay.core;

import java.math.BigDecimal;

/** One raw DRC OSD report, with the original message timestamp in UTC milliseconds. */
public final class OsdFrame {
    public final long timestampMs;
    public final String droneSn;
    public final BigDecimal latitude;
    public final BigDecimal longitude;
    public final Float height;
    public final Float attitudeHead;
    public final Double gimbalPitch;
    public final Double gimbalRoll;
    public final Double gimbalYaw;

    /** Keeps source values unchanged; invalid frames are rejected by the projection calculator. */
    public OsdFrame(long timestampMs, String droneSn, BigDecimal latitude, BigDecimal longitude,
                    Float height, Float attitudeHead, Double gimbalPitch, Double gimbalRoll,
                    Double gimbalYaw) {
        this.timestampMs = timestampMs;
        this.droneSn = droneSn;
        this.latitude = latitude;
        this.longitude = longitude;
        this.height = height;
        this.attitudeHead = attitudeHead;
        this.gimbalPitch = gimbalPitch;
        this.gimbalRoll = gimbalRoll;
        this.gimbalYaw = gimbalYaw;
    }
}
