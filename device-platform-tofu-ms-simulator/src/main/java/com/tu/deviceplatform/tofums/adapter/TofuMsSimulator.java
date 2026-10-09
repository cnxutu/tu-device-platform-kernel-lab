package com.tu.deviceplatform.tofums.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.tofums.core.OpticalSimulation;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only TJSON TCP server, independently implemented for adapter contract testing. */
public final class TofuMsSimulator implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpticalSimulation optical = new OpticalSimulation();
    private final ServerSocket server;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Receipt> receipts = new ArrayBlockingQueue<>(256);
    private final AtomicInteger rejectedFrames = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> daemon(r, "tofu-sim-client"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "tofu-sim-state"));
    private final Thread acceptor;
    private volatile boolean closed;
    private volatile boolean heartbeatReplies = true;
    private volatile boolean heartbeatStatusReply;

    public TofuMsSimulator(int port) throws IOException {
        server = new ServerSocket(); server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        acceptor = daemon(this::acceptLoop, "tofu-sim-accept"); acceptor.start();
        ticker.scheduleAtFixedRate(this::tick, 200, 200, TimeUnit.MILLISECONDS);
    }
    public int getPort() { return server.getLocalPort(); }
    public int getClientCount() { return clients.size(); }
    public int getRejectedFrames() { return rejectedFrames.get(); }
    public void setHeartbeatReplies(boolean enabled) { heartbeatReplies = enabled; }
    public void setHeartbeatStatusReply(boolean enabled) { heartbeatStatusReply = enabled; }
    public Receipt awaitReceipt(long millis) throws InterruptedException { return receipts.poll(millis, TimeUnit.MILLISECONDS); }
    public void disconnectClients() { clients.forEach(Client::close); optical.resetMotion(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept(); socket.setTcpNoDelay(true);
                if (clients.size() >= 16) { socket.close(); continue; }
                Client client = new Client(socket); clients.add(client); workers.execute(client::readLoop);
            } catch (IOException e) { if (!closed) { rejectedFrames.incrementAndGet(); } }
        }
    }
    private void tick() {
        try {
            byte[] state = mapper.writeValueAsBytes(optical.nextState(mapper));
            for (Client client : clients) {
                if (System.nanoTime() - client.lastHeartbeat > TimeUnit.SECONDS.toNanos(15)) { client.close(); }
                else { try { client.send(1, state); } catch (IOException e) { client.close(); } }
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot serialize simulated state", e); }
    }
    private final class Client {
        final Socket socket;
        volatile long lastHeartbeat = System.nanoTime();
        Client(Socket socket) { this.socket = socket; }
        void readLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (!closed && !socket.isClosed()) {
                    int first = in.read(); if (first < 0) { break; }
                    if (first != 0xEC || in.readUnsignedByte() != 0x91) { throw new IOException("Bad header"); }
                    int type = in.readUnsignedByte(); int length = in.readInt();
                    if (length < 0 || length > 1048576) { throw new IOException("Bad frame length"); }
                    if ((type == 17 && length != 0) || (type == 18 && length != 2)) { throw new IOException("Bad heartbeat/ACK length"); }
                    byte[] body = new byte[length]; in.readFully(body);
                    if (type == 17) {
                        lastHeartbeat = System.nanoTime();
                        if (heartbeatReplies) { send(17, heartbeatStatusReply ? new byte[] {0, 0} : new byte[0]); }
                    }
                    else if (type == 3) {
                        try {
                            JsonNode json = mapper.readTree(body); optical.apply(json);
                            receipts.offer(new Receipt(json, System.nanoTime()));
                            send(18, new byte[] {0, 0});
                        } catch (IllegalArgumentException | IOException e) { rejectedFrames.incrementAndGet(); send(18, new byte[] {0, 2}); }
                    } else if (type != 18) { rejectedFrames.incrementAndGet(); send(18, new byte[] {0, 2}); }
                }
            } catch (IOException e) { if (!socket.isClosed() && !closed) { rejectedFrames.incrementAndGet(); } }
            finally { close(); }
        }
        synchronized void send(int type, byte[] body) throws IOException {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(0xEC); out.writeByte(0x91); out.writeByte(type); out.writeInt(body.length); out.write(body); out.flush();
        }
        void close() {
            clients.remove(this);
            try { socket.close(); } catch (IOException e) { rejectedFrames.incrementAndGet(); }
        }
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException e) { rejectedFrames.incrementAndGet(); }
        disconnectClients(); ticker.shutdownNow(); workers.shutdownNow();
        try { acceptor.join(2000); workers.awaitTermination(2, TimeUnit.SECONDS); ticker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static Thread daemon(Runnable run, String name) { Thread t = new Thread(run, name); t.setDaemon(true); return t; }
    public static final class Receipt {
        public final JsonNode payload;
        public final long receivedNanos;
        Receipt(JsonNode payload, long nanos) { this.payload = payload; this.receivedNanos = nanos; }
    }
}
