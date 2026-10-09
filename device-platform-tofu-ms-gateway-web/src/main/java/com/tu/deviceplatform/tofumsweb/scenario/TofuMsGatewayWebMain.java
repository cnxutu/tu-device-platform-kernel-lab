package com.tu.deviceplatform.tofumsweb.scenario;

import com.tu.deviceplatform.tofumsweb.adapter.GatewayWebServer;
import com.tu.deviceplatform.tofumsweb.core.GatewayWebLab;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

public final class TofuMsGatewayWebMain {
    private TofuMsGatewayWebMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || "--help".equals(args[0])) {
            System.out.println("Usage: TofuMsGatewayWebMain <TOFU-MS.json path> [httpPort=18090]");
            return;
        }
        int port = args.length == 2 ? Integer.parseInt(args[1]) : 18090;
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("HTTP port 1..65535 required"); }
        try (GatewayWebLab lab = new GatewayWebLab(Paths.get(args[0])); GatewayWebServer server = new GatewayWebServer(lab, port)) {
            Thread hook = new Thread(server::close, "tofu-web-shutdown"); Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("TOFU_MS_GATEWAY_WEB http://127.0.0.1:" + server.port() + "/");
            System.out.println("No target connected. Choose a target in the browser; TCP write and ACK do not confirm physical execution.");
            try { new CountDownLatch(1).await(); }
            finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException e) { System.out.println("JVM shutdown in progress"); }
            }
        }
    }
}
