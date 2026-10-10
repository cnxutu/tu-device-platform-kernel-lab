package com.tu.deviceplatform.tofumsclient.scenario;

import com.tu.deviceplatform.tofumsclient.adapter.TofuMsControlClient;
import com.tu.deviceplatform.tofumsclient.core.TimedOpticalControl;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Consumer;

public final class TofuMsCommandClientMain {
    private TofuMsCommandClientMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) { usage(); return; }
        if (args.length < 2 || args.length > 3) { usage(); throw new IllegalArgumentException("Explicit target host and port required"); }
        Consumer<String> output = message -> System.out.println(Instant.now() + " " + message);
        try (TofuMsControlClient client = new TofuMsControlClient(args[0], Integer.parseInt(args[1]), args.length == 3 ? Integer.parseInt(args[2]) : 1, output);
             TimedOpticalControl control = new TimedOpticalControl(client, output)) {
            Thread shutdown = new Thread(control::close, "tofu-command-shutdown"); Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                output.accept("TARGET " + args[0] + ":" + args[1] + "; no video connection; ACK does not confirm physical execution");
                usage();
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = input.readLine()) != null) {
                    String command = line.trim().toLowerCase(Locale.ROOT);
                    if ("quit".equals(command) || "exit".equals(command)) { break; }
                    if (command.isEmpty()) { continue; }
                    try { execute(command, client, control); }
                    catch (IllegalArgumentException | IllegalStateException e) { output.accept("INPUT_ERROR " + e.getMessage()); }
                }
            } finally {
                control.close();
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException e) { output.accept("JVM shutdown in progress"); }
            }
        }
    }
    static void execute(String line, TofuMsControlClient client, TimedOpticalControl control) {
        String[] words = line.split("\\s+");
        switch (words[0]) {
            case "help": require(words, 1); usage(); break;
            case "status": require(words, 1); System.out.println("ONLINE=" + client.isOnline()); break;
            case "ptz":
                require(words, 4);
                String[] directions = {"", "left", "right", "up", "down", "left-up", "left-down", "right-up", "right-down"};
                int action = 0; for (int i = 1; i < directions.length; i++) { if (directions[i].equals(words[1])) { action = i; } }
                control.move("ptz_control", action, Integer.valueOf(words[2]), Long.parseLong(words[3])); break;
            case "zoom":
                require(words, 4);
                control.move("zoom_control", lensAction(words[1], "in", "out"), Integer.valueOf(words[2]), Long.parseLong(words[3])); break;
            case "focus":
                require(words, 3);
                control.move("focus_control", lensAction(words[1], "far", "near"), null, Long.parseLong(words[2])); break;
            case "stop":
                if (words.length < 1 || words.length > 2) { throw new IllegalArgumentException("stop [ptz|zoom|focus|all]"); }
                control.stop(words.length == 1 || "all".equals(words[1]) ? null : identifier(words[1])); break;
            default: throw new IllegalArgumentException("Unknown command; type help");
        }
    }
    private static String identifier(String actuator) {
        switch (actuator) {
            case "ptz": return "ptz_control";
            case "zoom": return "zoom_control";
            case "focus": return "focus_control";
            default: throw new IllegalArgumentException("Expected ptz, zoom, focus or all");
        }
    }
    private static int lensAction(String value, String increase, String decrease) {
        if (increase.equals(value)) { return 1; }
        if (decrease.equals(value)) { return 2; }
        throw new IllegalArgumentException("Expected " + increase + " or " + decrease);
    }
    private static void require(String[] words, int count) { if (words.length != count) { throw new IllegalArgumentException("Wrong argument count; type help"); } }
    private static void usage() {
        System.out.println("Usage: java -cp <client-jar;lib/*> com.tu.deviceplatform.tofumsclient.scenario.TofuMsCommandClientMain <host> <port> [ptzAddress]");
        System.out.println("ptz <left|right|up|down|left-up|left-down|right-up|right-down> <speedPercent> <durationMs>");
        System.out.println("zoom <in|out> <speedPercent> <durationMs> | focus <far|near> <durationMs>");
        System.out.println("stop [ptz|zoom|focus|all] | status | help | quit");
        System.out.println("Movement requires ONLINE; speed 1..100; duration 100..10000ms. One actuator at a time.");
    }
}
