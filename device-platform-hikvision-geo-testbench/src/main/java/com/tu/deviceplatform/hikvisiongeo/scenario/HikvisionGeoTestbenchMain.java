package com.tu.deviceplatform.hikvisiongeo.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.hikvisiongeo.adapter.HikvisionGeoTestbenchServer;
import com.tu.deviceplatform.hikvisiongeo.adapter.P2ServiceClient;
import com.tu.deviceplatform.hikvisiongeo.core.HikvisionCommandPreview;
import com.tu.deviceplatform.hikvisiongeo.core.HikvisionGeoSession;
import com.xmkj.codec.adapter.uav.device.hikvision.xml.HikvisionXmlCodec;

import java.util.concurrent.CountDownLatch;

public final class HikvisionGeoTestbenchMain {
    private static final String DEFAULT_P2_BASE_URL = "http://192.168.1.123:48084";

    private HikvisionGeoTestbenchMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("Usage: HikvisionGeoTestbenchMain [httpPort=18092]");
            System.out.println("P2 base URL may be set with HIKVISION_GEO_P2_BASE_URL; Authorization is read only from HIKVISION_GEO_P2_AUTHORIZATION.");
            return;
        }
        int port = args.length == 0 ? 18092 : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("HTTP port must be between 1 and 65535");
        }
        ObjectMapper mapper = new ObjectMapper();
        P2ServiceClient p2 = new P2ServiceClient(mapper,
                resolveP2BaseUrl(System.getenv("HIKVISION_GEO_P2_BASE_URL")),
                System.getenv("HIKVISION_GEO_P2_AUTHORIZATION"));
        HikvisionGeoSession session = new HikvisionGeoSession(mapper, p2,
                new HikvisionCommandPreview(new HikvisionXmlCodec()));
        try (HikvisionGeoTestbenchServer server = new HikvisionGeoTestbenchServer(mapper, session, port)) {
            Thread hook = new Thread(server::close, "hikvision-geo-testbench-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("HIKVISION_GEO_TESTBENCH http://127.0.0.1:" + server.port() + "/");
            System.out.println("No P2 request is sent until an explicit action is selected in the page.");
            try {
                new CountDownLatch(1).await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already in progress.
                }
            }
        }
    }

    static String resolveP2BaseUrl(String configuredBaseUrl) {
        return configuredBaseUrl == null || configuredBaseUrl.trim().isEmpty()
                ? DEFAULT_P2_BASE_URL : configuredBaseUrl;
    }
}
