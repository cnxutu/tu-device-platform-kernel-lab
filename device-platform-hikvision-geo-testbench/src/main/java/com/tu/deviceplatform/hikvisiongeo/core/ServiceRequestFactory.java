package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public final class ServiceRequestFactory {
    private static final List<String> IDENTIFIERS = Arrays.asList("target_acquire", "target_follow");

    private ServiceRequestFactory() {
    }

    public static GeoPointGenerator.Point point(ObjectNode request) {
        JsonNode geo = request.path("params").path("position").path("geo");
        return new GeoPointGenerator.Point(geo.path("longitude").decimalValue(),
                geo.path("latitude").decimalValue(), geo.path("altitude").decimalValue());
    }

    public static ObjectNode withPoint(ObjectNode source, String identifier,
                                       GeoPointGenerator.Point point) {
        if (!IDENTIFIERS.contains(identifier)) {
            throw new IllegalArgumentException("Unsupported target operation");
        }
        GeoPointGenerator.validate(point);
        ObjectNode request = source.deepCopy();
        request.put("identifier", identifier);
        ObjectNode position = (ObjectNode) request.path("params").path("position");
        ObjectNode geo = (ObjectNode) position.path("geo");
        geo.put("longitude", decimal(point.getLongitude()));
        geo.put("latitude", decimal(point.getLatitude()));
        geo.put("altitude", decimal(point.getAltitude()));
        return request;
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
