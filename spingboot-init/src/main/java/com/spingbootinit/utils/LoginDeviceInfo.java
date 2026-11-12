package com.spingbootinit.utils;

import ua_parser.Client;
import ua_parser.Parser;

import java.util.Locale;

/** 根据登录请求的 User-Agent 给出粗略设备信息；它可被客户端修改。 */
public record LoginDeviceInfo(String device, String os, String browser) {
    private static final Parser PARSER = new Parser();

    public static LoginDeviceInfo fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new LoginDeviceInfo("未知设备", "未知系统", "未知浏览器");
        }
        String value = userAgent.length() > 1024 ? userAgent.substring(0, 1024) : userAgent;
        Client parsed = PARSER.parse(value);
        String lower = value.toLowerCase(Locale.ROOT);
        String device = lower.contains("tablet") || lower.contains("ipad") ? "平板"
                : lower.contains("mobile") || lower.contains("iphone") || lower.contains("android") ? "手机"
                : "电脑";
        return new LoginDeviceInfo(device, limit(parsed.os.family, "未知系统"),
                limit(parsed.userAgent.family, "未知浏览器"));
    }

    private static String limit(String value, String fallback) {
        if (value == null || value.isBlank() || "Other".equalsIgnoreCase(value)) return fallback;
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
