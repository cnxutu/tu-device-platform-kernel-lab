package com.tu.deviceplatform.tofums.scenario;

import com.tu.deviceplatform.tofums.adapter.TofuMsSimulator;

public final class TofuMsSimulatorMain {
    private TofuMsSimulatorMain() { }
    public static void main(String[] args) throws Exception {
        TofuMsSimulator simulator = new TofuMsSimulator(args.length == 0 ? 8089 : Integer.parseInt(args[0]));
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close));
        System.out.println("Tofu MS simulator listening at 127.0.0.1:" + simulator.getPort());
        while (!Thread.currentThread().isInterrupted()) {
            TofuMsSimulator.Receipt receipt = simulator.awaitReceipt(1000);
            if (receipt != null) { System.out.println("Received " + receipt.payload); }
        }
    }
}
