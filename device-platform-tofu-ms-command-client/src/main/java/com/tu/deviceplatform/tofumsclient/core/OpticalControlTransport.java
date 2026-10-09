package com.tu.deviceplatform.tofumsclient.core;

/** A successful send means TCP write completed, not physical execution. */
public interface OpticalControlTransport extends AutoCloseable {
    boolean isOnline();
    long getConnectionGeneration();
    void send(String identifier, int action, Integer speed);
    @Override void close();
}
