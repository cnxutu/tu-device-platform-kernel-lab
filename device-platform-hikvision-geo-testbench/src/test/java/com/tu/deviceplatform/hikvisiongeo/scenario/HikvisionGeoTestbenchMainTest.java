package com.tu.deviceplatform.hikvisiongeo.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HikvisionGeoTestbenchMainTest {
    @Test
    void defaultsP2AddressAndHonorsConfiguredOverride() {
        assertEquals("http://192.168.1.123:48084", HikvisionGeoTestbenchMain.resolveP2BaseUrl(null));
        assertEquals("http://p2.example.test:48084",
                HikvisionGeoTestbenchMain.resolveP2BaseUrl("http://p2.example.test:48084"));
        assertEquals("http://192.168.1.123:48084", HikvisionGeoTestbenchMain.resolveP2BaseUrl(" "));
    }
}
