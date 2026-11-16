package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 测试台内部的 GEO → 海康 absoluteEx XML/curl 预览，不建立设备连接，也不执行 curl。
 *
 * <p>规则参考独立海康插件 ad-iot-codec-adapter-hmtd6368100c4l 的
 * HikvisionControlMapper.targetAcquire/targetFollow、gpsObjectInfo、coordinate，
 * 以及 PtzAbsoluteExXml 的字段名；参考版本为 release/1.0.2@da26cc15。
 * 本类只实现测试台支持的 GEO 分支，不复制 SDK DTO、PT 回退、XML 反序列化或插件运行时。
 * XML 通过 JDK StAX 写出，保持模块只依赖 Jackson JSON，不需要安装适配器制品。
 */
public final class HikvisionCommandPreview {
    private static final String PATH_FORMAT = "/ISAPI/PTZCtrl/channels/%d/absoluteEx";
    private static final String XML_NAMESPACE = "http://www.hikvision.com/ver20/XMLSchema";
    private static final int ACQUIRE_OBJECT_ID = 1;
    private static final int FOLLOW_OBJECT_ID = 123;
    // 与参考适配器保持一致。50/50 是适配层暂定默认值，不是本测试台实机验证过的推荐速度。
    private static final int ACQUIRE_SPEED = 50;

    /** 平台请求保持原样；只生成与该请求对应的路径、XML 和使用环境变量的 curl 文本。 */
    public Preview create(ObjectNode request) {
        String identifier = request.path("identifier").asText();
        if (!"target_acquire".equals(identifier) && !"target_follow".equals(identifier)) {
            throw new IllegalArgumentException("Unsupported target operation");
        }
        boolean acquire = "target_acquire".equals(identifier);
        JsonNode params = request.path("params");
        if (params.path("targetId").asText("").trim().isEmpty()) {
            throw new IllegalArgumentException("targetId is required");
        }
        String channelId = params.hasNonNull("channelId") ? params.path("channelId").asText() : null;
        JsonNode geo = params.path("position").path("geo");
        BigDecimal longitude = decimal(geo, "longitude");
        BigDecimal latitude = decimal(geo, "latitude");
        BigDecimal altitude = decimal(geo, "altitude");
        if (longitude.abs().compareTo(new BigDecimal("180")) > 0
                || latitude.abs().compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("GEO coordinates are out of range");
        }
        String path = String.format(PATH_FORMAT, channel(channelId));
        String xml = gpsXml(acquire, longitude, latitude, altitude);
        String curl = "curl -X PUT --digest --user \"$HIKVISION_USER\" "
                + "-H \"Content-Type: application/xml\" --data-binary "
                + bashQuote(xml) + " \"$HIKVISION_DEVICE_BASE_URL" + path + "\"";
        return new Preview(path, xml, curl);
    }

    /**
     * 同一 GEO 结构只有操作相关字段不同：acquire 补速度且不连续跟踪，follow 连续跟踪且不补速度。
     * targetId 只校验平台身份，不写成设备 objectInfo.id；存在 PT 字段时仍以 GEO 为准。
     */
    private String gpsXml(boolean acquire, BigDecimal longitude, BigDecimal latitude, BigDecimal altitude) {
        StringWriter output = new StringWriter();
        try {
            // 每次预览使用独立 writer，不共享可变 XML 状态；只写出，不解析导入的 XML。
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            try {
                writer.writeStartElement("PTZAbsoluteEx");
                writer.writeAttribute("version", "2.0");
                writer.writeDefaultNamespace(XML_NAMESPACE);
                if (acquire) {
                    element(writer, "horizontalSpeed", ACQUIRE_SPEED);
                    element(writer, "verticalSpeed", ACQUIRE_SPEED);
                }
                element(writer, "isContinuousTrackingEnabled", !acquire);
                element(writer, "locationMode", "GPS");
                writer.writeStartElement("objectInfo");
                element(writer, "id", acquire ? ACQUIRE_OBJECT_ID : FOLLOW_OBJECT_ID);
                writer.writeStartElement("positionInfo");
                element(writer, "longitudeType", longitude.signum() < 0 ? "W" : "E");
                element(writer, "latitudeType", latitude.signum() < 0 ? "S" : "N");
                coordinate(writer, "longitude", longitude);
                coordinate(writer, "latitude", latitude);
                element(writer, "altitude", altitude.toPlainString());
                writer.writeEndElement(); // positionInfo
                writer.writeEndElement(); // objectInfo
                writer.writeEndElement(); // PTZAbsoluteEx
                writer.flush();
                return output.toString();
            } finally {
                writer.close();
            }
        } catch (XMLStreamException failure) {
            throw new IllegalStateException("Failed to generate Hikvision GEO XML preview", failure);
        }
    }

    /**
     * 对照参考插件 coordinate：方向另写 E/W/N/S，此处取绝对值转度分秒。
     * 秒保留 6 位并四舍五入；若秒舍入成 60，要向分进位，分到 60 再向度进位。
     */
    private void coordinate(XMLStreamWriter writer, String name, BigDecimal decimalDegrees)
            throws XMLStreamException {
        BigDecimal absolute = decimalDegrees.abs();
        int degree = absolute.intValue();
        BigDecimal totalMinutes = absolute.subtract(BigDecimal.valueOf(degree)).multiply(BigDecimal.valueOf(60));
        int minute = totalMinutes.intValue();
        BigDecimal seconds = totalMinutes.subtract(BigDecimal.valueOf(minute))
                .multiply(BigDecimal.valueOf(60)).setScale(6, RoundingMode.HALF_UP);
        if (seconds.compareTo(BigDecimal.valueOf(60)) >= 0) {
            seconds = BigDecimal.ZERO;
            minute++;
        }
        if (minute >= 60) {
            minute = 0;
            degree++;
        }
        writer.writeStartElement(name);
        element(writer, "degree", degree);
        element(writer, "minute", minute);
        element(writer, "sec", seconds.stripTrailingZeros().toPlainString());
        writer.writeEndElement();
    }

    /** 使用 XML writer 转义文本，避免通过字符串拼接手工处理 XML 特殊字符。 */
    private void element(XMLStreamWriter writer, String name, Object value) throws XMLStreamException {
        writer.writeStartElement(name);
        writer.writeCharacters(value.toString());
        writer.writeEndElement();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Missing or non-numeric GEO " + field);
        }
        return value.decimalValue();
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
