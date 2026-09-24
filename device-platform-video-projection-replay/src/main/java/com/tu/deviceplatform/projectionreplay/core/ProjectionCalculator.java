package com.tu.deviceplatform.projectionreplay.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone port of P1 DeviceViewConeGeometryCalculator (geometryVersion 2).
 * Input validation, camera frame, ray intersection, corner order and mode thresholds
 * follow P1. Offline mode state uses OSD event time in place of P1 wall-clock TTL.
 */
public final class ProjectionCalculator {
    private static final double METERS_PER_DEGREE_LAT = 111_320.0D;
    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final long MODE_TTL_MS = 120_000L;
    private final double hFov;
    private final double vFov;
    private final double transitionPitch;
    private final double hysteresis;
    private final double yawOffset;
    private final double rollOffset;
    private final double rollDirection;
    private String lastSn;
    private String mode;
    private long lastTime;

    /** Supplies the same field calibration used by the live P1 projection. */
    public ProjectionCalculator(double hFov, double vFov, double transitionPitch, double hysteresis,
                                double yawOffset, double rollOffset, double rollDirection) {
        if (!(hFov > 0 && hFov < 180 && vFov > 0 && vFov < 180)) {
            throw new IllegalArgumentException("FOV must be between 0 and 180 degrees");
        }
        this.hFov = hFov;
        this.vFov = vFov;
        this.transitionPitch = transitionPitch;
        this.hysteresis = hysteresis;
        this.yawOffset = yawOffset;
        this.rollOffset = rollOffset;
        this.rollDirection = rollDirection >= 0 ? 1 : -1;
    }

    /** Calculates one frame in timestamp order; null means no valid geometry. */
    public Map<String, Object> calculate(OsdFrame sample) {
        if (sample.latitude == null || sample.longitude == null || sample.height == null
                || !Float.isFinite(sample.height) || sample.height <= 0
                || sample.latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || sample.longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            return null;
        }
        Double heading = valid(sample.gimbalYaw) ? sample.gimbalYaw
                : valid(sample.attitudeHead) ? sample.attitudeHead.doubleValue() : null;
        if (heading == null || sample.gimbalPitch != null && !valid(sample.gimbalPitch)
                || sample.gimbalRoll != null && !valid(sample.gimbalRoll)) {
            return null;
        }
        double pitch = sample.gimbalPitch == null ? -90 : sample.gimbalPitch;
        if (pitch < -90 || pitch > 90) {
            return null;
        }
        double roll = signed((sample.gimbalRoll == null ? 0 : sample.gimbalRoll) * rollDirection + rollOffset);
        heading = normalized(heading + yawOffset);
        updateMode(sample.droneSn, sample.timestampMs, pitch);
        Frame frame = Frame.of(heading, pitch, roll);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", sample.timestampMs);
        result.put("reportTimeMs", sample.timestampMs);
        result.put("sn", sample.droneSn);
        result.put("latitude", sample.latitude);
        result.put("longitude", sample.longitude);
        result.put("height", sample.height);
        result.put("attitudeHead", sample.attitudeHead);
        result.put("gimbalPitch", sample.gimbalPitch);
        result.put("gimbalYaw", sample.gimbalYaw);
        result.put("gimbalRoll", sample.gimbalRoll);
        result.put("resolvedHeading", heading);
        result.put("resolvedPitch", pitch);
        result.put("resolvedRoll", roll);
        result.put("viewMode", mode);
        result.put("cornerOrder", "CAMERA_TL_TR_BR_BL");
        result.put("geometryVersion", 2);
        result.put("frameCenter", null);
        result.put("corners", null);
        result.put("spatialCenter", null);
        result.put("spatialCorners", null);
        if ("PLANAR".equals(mode)) {
            Map<String, BigDecimal> center = ground(sample, frame.forward);
            double halfWidth = Math.tan(Math.toRadians(hFov / 2));
            double halfHeight = Math.tan(Math.toRadians(vFov / 2));
            List<Map<String, BigDecimal>> corners = Arrays.asList(
                    ground(sample, frame.ray(-halfWidth, halfHeight)),
                    ground(sample, frame.ray(halfWidth, halfHeight)),
                    ground(sample, frame.ray(halfWidth, -halfHeight)),
                    ground(sample, frame.ray(-halfWidth, -halfHeight)));
            if (center == null || corners.contains(null)) {
                return null;
            }
            result.put("frameCenter", center);
            result.put("corners", corners);
        } else {
            double distance = sample.height.doubleValue();
            double halfWidth = distance * Math.tan(Math.toRadians(hFov / 2));
            double halfHeight = distance * Math.tan(Math.toRadians(vFov / 2));
            Vec center = frame.forward.scale(distance);
            List<Map<String, BigDecimal>> corners = new ArrayList<>();
            for (Vec offset : Arrays.asList(
                    center.add(frame.right.scale(-halfWidth)).add(frame.up.scale(halfHeight)),
                    center.add(frame.right.scale(halfWidth)).add(frame.up.scale(halfHeight)),
                    center.add(frame.right.scale(halfWidth)).add(frame.up.scale(-halfHeight)),
                    center.add(frame.right.scale(-halfWidth)).add(frame.up.scale(-halfHeight)))) {
                corners.add(spatial(sample, offset));
            }
            result.put("spatialCenter", spatial(sample, center));
            result.put("spatialCorners", corners);
        }
        return result;
    }

    private void updateMode(String sn, long time, double pitch) {
        if (mode == null || !java.util.Objects.equals(lastSn, sn) || time - lastTime >= MODE_TTL_MS) {
            mode = pitch <= transitionPitch ? "PLANAR" : "SPATIAL";
        } else if ("PLANAR".equals(mode) && pitch > transitionPitch + hysteresis) {
            mode = "SPATIAL";
        } else if ("SPATIAL".equals(mode) && pitch <= transitionPitch) {
            mode = "PLANAR";
        }
        lastSn = sn;
        lastTime = time;
    }

    private static boolean valid(Number angle) {
        return angle != null && Double.isFinite(angle.doubleValue()) && Math.abs(angle.doubleValue()) <= 720;
    }

    private static double normalized(double value) {
        double result = value % 360;
        return result < 0 ? result + 360 : result;
    }

    private static double signed(double value) {
        double result = value % 360;
        return result > 180 ? result - 360 : result <= -180 ? result + 360 : result;
    }

    private Map<String, BigDecimal> ground(OsdFrame sample, Vec ray) {
        if (ray.up >= -1.0E-8) {
            return null;
        }
        double distance = -sample.height / ray.up;
        return Double.isFinite(distance) && distance > 0 ? point(sample, ray.scale(distance)) : null;
    }

    private Map<String, BigDecimal> spatial(OsdFrame sample, Vec offset) {
        Map<String, BigDecimal> result = point(sample, offset);
        result.put("height", BigDecimal.valueOf(sample.height).add(BigDecimal.valueOf(offset.up), MATH));
        return result;
    }

    private Map<String, BigDecimal> point(OsdFrame sample, Vec offset) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (Math.abs(offset.east) < 1.0E-10 && Math.abs(offset.north) < 1.0E-10) {
            result.put("latitude", sample.latitude);
            result.put("longitude", sample.longitude);
            return result;
        }
        double metersPerLon = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(sample.latitude.doubleValue()));
        result.put("latitude", sample.latitude.add(BigDecimal.valueOf(offset.north / METERS_PER_DEGREE_LAT), MATH));
        result.put("longitude", sample.longitude.add(BigDecimal.valueOf(offset.east / metersPerLon), MATH));
        return result;
    }

    private static final class Frame {
        final Vec forward;
        final Vec right;
        final Vec up;

        private Frame(Vec forward, Vec right, Vec up) {
            this.forward = forward;
            this.right = right;
            this.up = up;
        }

        static Frame of(double headingDeg, double pitchDeg, double rollDeg) {
            double heading = Math.toRadians(headingDeg);
            double pitch = Math.toRadians(pitchDeg);
            Vec forward = new Vec(Math.sin(heading) * Math.cos(pitch),
                    Math.cos(heading) * Math.cos(pitch), Math.sin(pitch));
            Vec right = new Vec(Math.cos(heading), -Math.sin(heading), 0);
            Vec up = right.cross(forward).normalize();
            double roll = -Math.toRadians(rollDeg);
            return new Frame(forward, right.rotate(forward, roll), up.rotate(forward, roll));
        }

        Vec ray(double horizontal, double vertical) {
            return forward.add(right.scale(horizontal)).add(up.scale(vertical)).normalize();
        }
    }

    private static final class Vec {
        final double east;
        final double north;
        final double up;

        Vec(double east, double north, double up) {
            this.east = east;
            this.north = north;
            this.up = up;
        }

        Vec add(Vec other) { return new Vec(east + other.east, north + other.north, up + other.up); }
        Vec scale(double scalar) { return new Vec(east * scalar, north * scalar, up * scalar); }
        double dot(Vec other) { return east * other.east + north * other.north + up * other.up; }
        Vec cross(Vec other) {
            return new Vec(north * other.up - up * other.north, up * other.east - east * other.up,
                    east * other.north - north * other.east);
        }
        Vec normalize() { return scale(1 / Math.sqrt(dot(this))); }
        Vec rotate(Vec axis, double radians) {
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return scale(cos).add(axis.cross(this).scale(sin)).add(axis.scale(axis.dot(this) * (1 - cos)));
        }
    }
}
