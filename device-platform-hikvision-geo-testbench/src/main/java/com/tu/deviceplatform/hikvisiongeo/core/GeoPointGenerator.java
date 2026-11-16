package com.tu.deviceplatform.hikvisiongeo.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class GeoPointGenerator {
    public static final double MAX_RADIUS_METERS = 1000.0;
    private static final double EARTH_RADIUS_METERS = 6371008.8;
    private static final double PI = Math.PI;

    private GeoPointGenerator() {
    }

    public static List<Point> cardinal(Point center, double radiusMeters) {
        validateRadius(radiusMeters);
        validate(center);
        return Arrays.asList(
                destination(center, radiusMeters, 0.0),
                destination(center, radiusMeters, PI / 2.0),
                destination(center, radiusMeters, PI),
                destination(center, radiusMeters, PI * 1.5));
    }

    public static Point direction(Point center, double radiusMeters, String direction) {
        validateRadius(radiusMeters);
        validate(center);
        return destination(center, radiusMeters, bearing(direction));
    }

    public static Point randomAlongCardinal(Point center, double radiusMeters, String direction, Random random) {
        validateRadius(radiusMeters);
        validate(center);
        if (random == null) {
            throw new IllegalArgumentException("random is required");
        }
        double maxDistance = Math.max(0.0, radiusMeters - 0.01);
        double distance = random.nextDouble() * maxDistance;
        Point generated = destination(center, distance, cardinalBearing(direction));
        if ("N".equals(direction) || "S".equals(direction)) {
            return new Point(center.getLongitude(), generated.getLatitude(), center.getAltitude());
        }
        return new Point(generated.getLongitude(), center.getLatitude(), center.getAltitude());
    }

    private static double bearing(String direction) {
        double bearing;
        switch (direction == null ? "" : direction) {
            case "N": bearing = 0.0; break;
            case "NE": bearing = PI / 4.0; break;
            case "E": bearing = PI / 2.0; break;
            case "SE": bearing = PI * 3.0 / 4.0; break;
            case "S": bearing = PI; break;
            case "SW": bearing = PI * 5.0 / 4.0; break;
            case "W": bearing = PI * 1.5; break;
            case "NW": bearing = PI * 7.0 / 4.0; break;
            default: throw new IllegalArgumentException("direction must be one of N, NE, E, SE, S, SW, W, NW");
        }
        return bearing;
    }

    private static double cardinalBearing(String direction) {
        switch (direction == null ? "" : direction) {
            case "N": return 0.0;
            case "E": return PI / 2.0;
            case "S": return PI;
            case "W": return PI * 1.5;
            default: throw new IllegalArgumentException("direction must be one of N, E, S, W");
        }
    }

    public static Point randomWithin(Point center, double radiusMeters, Random random) {
        validateRadius(radiusMeters);
        validate(center);
        if (random == null) {
            throw new IllegalArgumentException("random is required");
        }
        double maxDistance = Math.max(0.0, radiusMeters - 0.01);
        double distance = maxDistance * Math.sqrt(random.nextDouble());
        double bearing = random.nextDouble() * 2.0 * PI;
        return destination(center, distance, bearing);
    }

    public static double distanceMeters(Point first, Point second) {
        validate(first);
        validate(second);
        double lat1 = Math.toRadians(first.getLatitude().doubleValue());
        double lat2 = Math.toRadians(second.getLatitude().doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(second.getLongitude().doubleValue()
                - first.getLongitude().doubleValue());
        double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        a = Math.max(0.0, Math.min(1.0, a));
        return 2.0 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    public static void validateRadius(double radiusMeters) {
        if (!Double.isFinite(radiusMeters) || radiusMeters < 1.0 || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("radiusMeters must be between 1 and 1000");
        }
    }

    public static void validate(Point point) {
        if (point == null || point.getLongitude() == null || point.getLatitude() == null
                || point.getAltitude() == null) {
            throw new IllegalArgumentException("GEO longitude, latitude and altitude are required");
        }
        if (point.getLongitude().compareTo(new BigDecimal("-180")) < 0
                || point.getLongitude().compareTo(new BigDecimal("180")) > 0) {
            throw new IllegalArgumentException("longitude must be in [-180, 180]");
        }
        if (point.getLatitude().compareTo(new BigDecimal("-90")) < 0
                || point.getLatitude().compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("latitude must be in [-90, 90]");
        }
    }

    private static Point destination(Point center, double distanceMeters, double bearing) {
        double latitude1 = Math.toRadians(center.getLatitude().doubleValue());
        double longitude1 = Math.toRadians(center.getLongitude().doubleValue());
        double angularDistance = distanceMeters / EARTH_RADIUS_METERS;
        double latitude2 = Math.asin(Math.sin(latitude1) * Math.cos(angularDistance)
                + Math.cos(latitude1) * Math.sin(angularDistance) * Math.cos(bearing));
        double longitude2 = longitude1 + Math.atan2(Math.sin(bearing) * Math.sin(angularDistance)
                * Math.cos(latitude1), Math.cos(angularDistance) - Math.sin(latitude1) * Math.sin(latitude2));
        longitude2 = (longitude2 + 3.0 * PI) % (2.0 * PI) - PI;
        return new Point(decimal(Math.toDegrees(longitude2)), decimal(Math.toDegrees(latitude2)),
                center.getAltitude());
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(9, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static final class Point {
        private final BigDecimal longitude;
        private final BigDecimal latitude;
        private final BigDecimal altitude;

        public Point(BigDecimal longitude, BigDecimal latitude, BigDecimal altitude) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.altitude = altitude;
        }

        public BigDecimal getLongitude() {
            return longitude;
        }

        public BigDecimal getLatitude() {
            return latitude;
        }

        public BigDecimal getAltitude() {
            return altitude;
        }
    }
}
