package com.tu.deviceplatform.hikvisiongeo.core;

public interface ConfigurableServiceInvoker extends ServiceInvoker {
    void configureBaseUrl(String baseUrl);
    boolean hasAuthorization();
}
