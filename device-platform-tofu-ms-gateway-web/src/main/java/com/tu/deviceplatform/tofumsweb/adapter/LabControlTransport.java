package com.tu.deviceplatform.tofumsweb.adapter;

import com.tu.deviceplatform.tofumsclient.core.OpticalControlTransport;
import java.util.Map;

public interface LabControlTransport extends OpticalControlTransport {
    Map<String, Object> snapshot();
}
