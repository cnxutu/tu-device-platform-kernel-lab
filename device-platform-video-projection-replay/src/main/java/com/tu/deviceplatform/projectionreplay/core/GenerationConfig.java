package com.tu.deviceplatform.projectionreplay.core;

import java.util.List;

/** Demo input contract. All absolute times are UTC epoch milliseconds. */
public final class GenerationConfig {
    public String droneSn;
    public long originalVideoStartMs;
    public double frameHfovDeg = 60;
    public double frameVfovDeg = 40;
    public double pitchTransitionDeg = -60;
    public double pitchHysteresisDeg = 1;
    public double yawOffsetDeg;
    public double rollOffsetDeg;
    public double rollDirection = 1;
    public List<Segment> segments;

    /** Exact source interval and the exported clip's observed media duration. */
    public static final class Segment {
        public String id;
        public String videoFile;
        public long sourceStartMs;
        public long sourceEndMs;
        public long durationMs;
        public long syncOffsetMs;
    }
}
