package com.tu.deviceplatform.tofumsclient.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One motion at a time; duration starts after TCP write, with no movement retries. */
public final class TimedOpticalControl implements AutoCloseable {
    private final OpticalControlTransport client;
    private final Consumer<String> output;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tofu-command-control"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private final AtomicBoolean motionReserved = new AtomicBoolean();
    // Accessed only by worker. Retained when STOP fails so cleanup can attempt it once more.
    private String activeIdentifier;
    private ScheduledFuture<?> stopTimer;

    public TimedOpticalControl(OpticalControlTransport client, Consumer<String> output) { this.client = client; this.output = output; }

    public boolean isMotionActive() { return motionReserved.get(); }

    public synchronized CompletableFuture<Void> move(String identifier, int action, Integer speed, long durationMillis) {
        validate(identifier, action, speed, durationMillis);
        if (closed) { throw new IllegalStateException("Control client closed"); }
        if (!client.isOnline()) { throw new IllegalStateException("Device is offline; movement was not queued"); }
        if (!motionReserved.compareAndSet(false, true)) { throw new IllegalStateException("Stop the active actuator before another movement"); }
        long generation = client.getConnectionGeneration();
        return submit(() -> {
            if (!client.isOnline() || generation != client.getConnectionGeneration()) {
                motionReserved.set(false);
                throw new IllegalStateException("Connection changed before send; movement cancelled");
            }
            activeIdentifier = identifier;
            try {
                client.send(identifier, action, speed);
                output.accept("AUTO_STOP armed durationMillis=" + durationMillis);
                stopTimer = worker.schedule(() -> {
                    try { stopActive(); }
                    catch (RuntimeException e) { output.accept("STOP_FAILED " + e.getMessage() + "; physical motion may continue"); }
                }, durationMillis, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                // A failed write may still have reached the device. Attempt STOP, never retry movement.
                try { stopActive(); } catch (RuntimeException stopFailure) { e.addSuppressed(stopFailure); }
                throw e;
            }
        });
    }

    public synchronized CompletableFuture<Void> stop(String identifier) {
        if (identifier != null) { validateIdentifier(identifier); }
        return submit(() -> {
            if (identifier == null) {
                RuntimeException failure = null;
                for (String scope : new String[] {"ptz_control", "zoom_control", "optical_focus_control"}) {
                    try { stopScope(scope); } catch (RuntimeException e) {
                        if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
                    }
                }
                if (failure != null) { throw failure; }
            } else { stopScope(identifier); }
        });
    }

    private CompletableFuture<Void> submit(Runnable action) {
        if (closed) { throw new IllegalStateException("Control client closed"); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        worker.execute(() -> {
            try { action.run(); result.complete(null); }
            catch (RuntimeException e) { result.completeExceptionally(e); output.accept("COMMAND_FAILED " + e.getMessage()); }
        });
        return result;
    }
    private void stopActive() { if (activeIdentifier != null) { stopScope(activeIdentifier); } }
    private void stopScope(String identifier) {
        if (identifier.equals(activeIdentifier) && stopTimer != null) { stopTimer.cancel(false); stopTimer = null; }
        try { client.send(identifier, 0, null); }
        catch (RuntimeException e) {
            output.accept("STOP_FAILED " + identifier + ": " + e.getMessage() + "; physical motion may continue");
            throw e;
        }
        if (identifier.equals(activeIdentifier)) { activeIdentifier = null; motionReserved.set(false); }
    }
    private static void validate(String identifier, int action, Integer speed, long duration) {
        validateIdentifier(identifier);
        int maxAction = "ptz_control".equals(identifier) ? 8 : 2;
        if (action < 1 || action > maxAction || duration < 100 || duration > 10000) {
            throw new IllegalArgumentException("Movement action and duration 100..10000ms required");
        }
        if ("optical_focus_control".equals(identifier)) {
            if (speed != null) { throw new IllegalArgumentException("Focus has fixed speed"); }
        } else if (speed == null || speed < 1 || speed > 100) { throw new IllegalArgumentException("Speed 1..100 required"); }
    }
    private static void validateIdentifier(String identifier) {
        if (!"ptz_control".equals(identifier) && !"zoom_control".equals(identifier) && !"optical_focus_control".equals(identifier)) {
            throw new IllegalArgumentException("Unsupported actuator");
        }
    }
    @Override public synchronized void close() {
        if (closed) { return; }
        Future<?> cleanup = worker.submit(() -> {
            try { stopActive(); }
            catch (RuntimeException e) { output.accept("STOP_FAILED on exit: " + e.getMessage() + "; physical motion may continue"); }
        });
        try { cleanup.get(7000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); output.accept("Cleanup interrupted; STOP unconfirmed"); }
        catch (ExecutionException | TimeoutException e) { output.accept("Cleanup failed; STOP unconfirmed: " + e.getClass().getSimpleName()); }
        // Retain the worker and reservation if remote cleanup fails, so an explicit retry remains possible.
        client.close(); closed = true; worker.shutdownNow();
    }
}
