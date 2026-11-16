package com.tu.deviceplatform.hikvisiongeo.core;

public final class ServiceInvokeResult {
    private final int httpStatus;
    private final boolean accepted;
    private final String requestId;
    private final String responseBody;
    private final String transportError;

    public ServiceInvokeResult(int httpStatus, boolean accepted, String requestId,
                               String responseBody, String transportError) {
        this.httpStatus = httpStatus;
        this.accepted = accepted;
        this.requestId = requestId;
        this.responseBody = responseBody;
        this.transportError = transportError;
    }

    public int getHttpStatus() { return httpStatus; }
    public boolean isAccepted() { return accepted; }
    public String getRequestId() { return requestId; }
    public String getResponseBody() { return responseBody; }
    public String getTransportError() { return transportError; }
}
