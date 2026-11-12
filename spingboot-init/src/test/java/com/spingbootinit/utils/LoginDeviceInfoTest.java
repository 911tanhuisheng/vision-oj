package com.spingbootinit.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LoginDeviceInfoTest {
    @Test
    void identifiesCoarseDesktopAndMobileInformation() {
        LoginDeviceInfo desktop = LoginDeviceInfo.fromUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        assertEquals("电脑", desktop.device());
        assertNotEquals("未知系统", desktop.os());
        assertNotEquals("未知浏览器", desktop.browser());

        LoginDeviceInfo mobile = LoginDeviceInfo.fromUserAgent(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        assertEquals("手机", mobile.device());
        assertNotEquals("未知系统", mobile.os());
    }
}
