package com.spingbootinit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IpRegionServiceTest {
    @Test
    void resolvesOfflineDatabaseAndDistinguishesLocalAddress() {
        IpRegionService service = new IpRegionService();
        service.init();
        try {
            assertEquals("内网/本机", service.estimate("127.0.0.1"));
            assertEquals("未知归属地", service.estimate("not-an-ip"));
            assertNotEquals("未知归属地", service.estimate("8.8.8.8"));
        } finally {
            service.close();
        }
    }
}
