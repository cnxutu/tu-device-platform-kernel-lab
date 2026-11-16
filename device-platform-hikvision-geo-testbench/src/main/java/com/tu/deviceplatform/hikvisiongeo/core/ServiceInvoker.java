package com.tu.deviceplatform.hikvisiongeo.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ServiceInvoker {
    ServiceInvokeResult invoke(ObjectNode request);
    boolean isConfigured();
    String getBaseUrl();
}
