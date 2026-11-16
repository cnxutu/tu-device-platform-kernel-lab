package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xmkj.codec.adapter.uav.device.hikvision.dto.xml.PtzAbsoluteExXml;
import com.xmkj.codec.adapter.uav.device.hikvision.mapper.HikvisionControlMapper;
import com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec;
import com.xmkj.sdk.service.ptz.OpticalTargetPosition;
import com.xmkj.sdk.service.ptz.TargetAcquireService;
import com.xmkj.sdk.service.ptz.TargetFollowService;

import java.math.BigDecimal;

public final class HikvisionCommandPreview {
    private static final String PATH_FORMAT = "/ISAPI/PTZCtrl/channels/%d/absoluteEx";

    private final HikvisionXmlCodec codec;

    public HikvisionCommandPreview(HikvisionXmlCodec codec) {
        this.codec = codec;
    }

    public Preview create(ObjectNode request) {
        String identifier = request.path("identifier").asText();
        ObjectNode params = (ObjectNode) request.path("params");
        String targetId = params.path("targetId").asText();
        String channelId = params.hasNonNull("channelId") ? params.path("channelId").asText() : null;
        OpticalTargetPosition position = position(params.path("position"));
        PtzAbsoluteExXml command;
        if ("target_acquire".equals(identifier)) {
            command = HikvisionControlMapper.targetAcquire(TargetAcquireService.builder()
                    .targetId(targetId).channelId(channelId).position(position).build());
        } else if ("target_follow".equals(identifier)) {
            command = HikvisionControlMapper.targetFollow(TargetFollowService.builder()
                    .targetId(targetId).channelId(channelId).position(position).build());
        } else {
            throw new IllegalArgumentException("Unsupported target operation");
        }
        String path = String.format(PATH_FORMAT, channel(channelId));
        String xml = codec.serialize(command);
        String curl = "curl -X PUT --digest --user \"$HIKVISION_USER\" "
                + "-H \"Content-Type: application/xml\" --data-binary "
                + bashQuote(xml) + " \"$HIKVISION_DEVICE_BASE_URL" + path + "\"";
        return new Preview(path, xml, curl);
    }

    private OpticalTargetPosition position(JsonNode value) {
        JsonNode geoNode = value.path("geo");
        OpticalTargetPosition.GeoPosition geo = OpticalTargetPosition.GeoPosition.builder()
                .longitude(decimal(geoNode, "longitude"))
                .latitude(decimal(geoNode, "latitude"))
                .altitude(decimal(geoNode, "altitude"))
                .build();
        OpticalTargetPosition.PtPosition pt = null;
        JsonNode ptNode = value.path("pt");
        if (ptNode.isObject()) {
            pt = OpticalTargetPosition.PtPosition.builder()
                    .azimuth(optionalDecimal(ptNode, "azimuth"))
                    .elevation(optionalDecimal(ptNode, "elevation"))
                    .objectDistance(optionalDecimal(ptNode, "objectDistance"))
                    .build();
        }
        return OpticalTargetPosition.builder().geo(geo).pt(pt).build();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal result = optionalDecimal(node, field);
        if (result == null) {
            throw new IllegalArgumentException("Missing GEO " + field);
        }
        return result;
    }

    private BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private int channel(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return 1;
        }
        if (!channelId.matches("[1-9][0-9]{0,3}")) {
            throw new IllegalArgumentException("channelId must be a positive channel number");
        }
        return Integer.parseInt(channelId);
    }

    private String bashQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static final class Preview {
        private final String path;
        private final String xml;
        private final String curl;

        Preview(String path, String xml, String curl) {
            this.path = path;
            this.xml = xml;
            this.curl = curl;
        }

        public String getPath() {
            return path;
        }

        public String getXml() {
            return xml;
        }

        public String getCurl() {
            return curl;
        }
    }
}
