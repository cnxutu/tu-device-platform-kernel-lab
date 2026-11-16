package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验测试台生成的 GEO XML 契约，不依赖 XML 库的空格、属性顺序或数值尾零格式。
 * 预期值对照独立海康插件的 targetAcquire/targetFollow、gpsObjectInfo、coordinate 映射。
 */
class HikvisionCommandPreviewTest {
    private final HikvisionCommandPreview preview = new HikvisionCommandPreview();

    @Test
    void acquireWritesGpsNamespaceDefaultSpeedsAndDeviceObjectId() throws Exception {
        ObjectNode request = HikvisionGeoCoreTest.request("target_acquire");
        ObjectNode original = request.deepCopy();
        HikvisionCommandPreview.Preview result = preview.create(request);
        Element root = xml(result).getDocumentElement();

        assertEquals("PTZAbsoluteEx", root.getLocalName());
        assertEquals("http://www.hikvision.com/ver20/XMLSchema", root.getNamespaceURI());
        assertEquals("2.0", root.getAttribute("version"));
        assertEquals("50", value(root, "horizontalSpeed"));
        assertEquals("50", value(root, "verticalSpeed"));
        assertEquals("false", value(root, "isContinuousTrackingEnabled"));
        assertEquals("GPS", value(root, "locationMode"));
        assertEquals("1", value(root, "id"));
        assertEquals("E", value(root, "longitudeType"));
        assertEquals("N", value(root, "latitudeType"));
        assertCoordinate(root, "longitude", 120, 1, "10.33932");
        assertCoordinate(root, "latitude", 30, 16, "52.27104");
        assertEquals(0, new BigDecimal("15").compareTo(new BigDecimal(value(root, "altitude"))));
        // 即使原请求带 PT，GEO 优先，不能把两种定位结构混写到设备 XML 中。
        assertEquals(0, root.getElementsByTagNameNS("*", "azimuth").getLength());
        assertEquals(0, root.getElementsByTagNameNS("*", "elevation").getLength());
        assertEquals(0, root.getElementsByTagNameNS("*", "objectDistance").getLength());
        assertEquals(original, request, "生成预览不能修改待发送的平台 JSON");
    }

    @Test
    void followUsesContinuousTrackingAndNegativeCoordinatesWithoutAcquireSpeeds() throws Exception {
        ObjectNode request = HikvisionGeoCoreTest.request("target_follow");
        geo(request).put("longitude", new BigDecimal("-120.02038"));
        geo(request).put("latitude", new BigDecimal("-30.281953"));
        geo(request).put("altitude", new BigDecimal("-5.25"));
        Element root = xml(preview.create(request)).getDocumentElement();

        assertEquals("true", value(root, "isContinuousTrackingEnabled"));
        assertEquals("123", value(root, "id"));
        assertEquals("W", value(root, "longitudeType"));
        assertEquals("S", value(root, "latitudeType"));
        assertCoordinate(root, "longitude", 120, 1, "13.368");
        assertCoordinate(root, "latitude", 30, 16, "55.0308");
        assertEquals("-5.25", value(root, "altitude"));
        assertEquals(0, root.getElementsByTagNameNS("*", "horizontalSpeed").getLength());
        assertEquals(0, root.getElementsByTagNameNS("*", "verticalSpeed").getLength());
    }

    @Test
    void roundsSecondsToSixDecimalsAndCarriesAcrossMinuteAndDegree() throws Exception {
        ObjectNode request = HikvisionGeoCoreTest.request("target_acquire");
        geo(request).put("longitude", new BigDecimal("179.99999999999"));
        geo(request).put("latitude", new BigDecimal("89.99999999999"));
        Element root = xml(preview.create(request)).getDocumentElement();

        assertCoordinate(root, "longitude", 180, 0, "0");
        assertCoordinate(root, "latitude", 90, 0, "0");
    }

    @Test
    void acceptsZeroAndInclusiveCoordinateBoundaries() throws Exception {
        ObjectNode request = HikvisionGeoCoreTest.request("target_follow");
        for (String[] pair : new String[][]{{"0", "0"}, {"180", "90"}, {"-180", "-90"}}) {
            geo(request).put("longitude", new BigDecimal(pair[0]));
            geo(request).put("latitude", new BigDecimal(pair[1]));
            Element root = xml(preview.create(request)).getDocumentElement();
            assertCoordinate(root, "longitude", Math.abs(Integer.parseInt(pair[0])), 0, "0");
            assertCoordinate(root, "latitude", Math.abs(Integer.parseInt(pair[1])), 0, "0");
            assertEquals(pair[0].startsWith("-") ? "W" : "E", value(root, "longitudeType"));
            assertEquals(pair[1].startsWith("-") ? "S" : "N", value(root, "latitudeType"));
        }
    }

    @Test
    void usesValidatedChannelForPathAndGeneratesPreviewOnlyCurl() {
        ObjectNode request = HikvisionGeoCoreTest.request("target_acquire");
        assertEquals("/ISAPI/PTZCtrl/channels/1/absoluteEx", preview.create(request).getPath());
        request.with("params").put("channelId", "2");
        HikvisionCommandPreview.Preview result = preview.create(request);
        assertEquals("/ISAPI/PTZCtrl/channels/2/absoluteEx", result.getPath());
        assertTrue(result.getCurl().startsWith("curl -X PUT --digest"));
        assertTrue(result.getCurl().contains("$HIKVISION_USER"));
        assertTrue(result.getCurl().contains("$HIKVISION_DEVICE_BASE_URL" + result.getPath()));
        assertTrue(result.getCurl().contains(result.getXml()));
        assertFalse(result.getCurl().contains("SIM-TARGET-001"), "平台 targetId 不应替代设备 objectInfo.id");
        for (String channel : new String[]{"0", "-1", "10000", "1/path"}) {
            request.with("params").put("channelId", channel);
            assertThrows(IllegalArgumentException.class, () -> preview.create(request), channel);
        }
    }

    @Test
    void rejectsInvalidTargetOperationAndMissingOrOutOfRangeGeo() {
        ObjectNode invalidOperation = HikvisionGeoCoreTest.request("unsupported");
        assertThrows(IllegalArgumentException.class, () -> preview.create(invalidOperation));
        ObjectNode blankTarget = HikvisionGeoCoreTest.request("target_acquire");
        blankTarget.with("params").put("targetId", " ");
        assertThrows(IllegalArgumentException.class, () -> preview.create(blankTarget));
        for (String field : new String[]{"longitude", "latitude", "altitude"}) {
            ObjectNode missing = HikvisionGeoCoreTest.request("target_acquire");
            geo(missing).remove(field);
            assertThrows(IllegalArgumentException.class, () -> preview.create(missing), field);
        }
        for (String[] pair : new String[][]{
                {"180.0001", "0"}, {"-180.0001", "0"}, {"0", "90.0001"}, {"0", "-90.0001"}}) {
            ObjectNode outside = HikvisionGeoCoreTest.request("target_follow");
            geo(outside).put("longitude", new BigDecimal(pair[0]));
            geo(outside).put("latitude", new BigDecimal(pair[1]));
            assertThrows(IllegalArgumentException.class, () -> preview.create(outside));
        }
    }

    private ObjectNode geo(ObjectNode request) {
        return (ObjectNode) request.path("params").path("position").path("geo");
    }

    private Document xml(HikvisionCommandPreview.Preview result) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(result.getXml())));
    }

    private String value(Element parent, String name) {
        return parent.getElementsByTagNameNS("*", name).item(0).getTextContent();
    }

    private void assertCoordinate(Element root, String name, int degree, int minute, String seconds) {
        Element coordinate = (Element) root.getElementsByTagNameNS("*", name).item(0);
        assertEquals(String.valueOf(degree), value(coordinate, "degree"));
        assertEquals(String.valueOf(minute), value(coordinate, "minute"));
        assertEquals(0, new BigDecimal(seconds).compareTo(new BigDecimal(value(coordinate, "sec"))));
    }
}
