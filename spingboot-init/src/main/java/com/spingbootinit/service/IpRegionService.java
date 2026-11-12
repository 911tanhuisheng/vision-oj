package com.spingbootinit.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetAddress;

/** 使用打包在应用中的离线 IPv4 数据库估算 IP 归属地。 */
@Service
@Slf4j
public class IpRegionService {
    private Ip2Region lookup;

    @PostConstruct
    public void init() {
        try (InputStream input = new ClassPathResource("ip2region_v4.xdb").getInputStream()) {
            Config v4 = Config.custom().setCachePolicy(Config.BufferCache)
                    .setXdbInputStream(input).asV4();
            lookup = Ip2Region.create(v4, null);
        } catch (Exception e) {
            log.warn("IP 归属地数据库不可用，管理员页面将显示未知归属地", e);
        }
    }

    public String estimate(String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return "未知归属地";
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isSiteLocalAddress() || address.isLinkLocalAddress() ||
                    address.isMulticastAddress()) return "内网/本机";
            if (lookup == null) return "未知归属地";
            String result = lookup.search(ip);
            if (result == null || result.isBlank()) return "未知归属地";
            String[] fields = result.split("\\|", -1);
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < Math.min(3, fields.length); i++) {
                String part = fields[i];
                if (part.isBlank() || "0".equals(part) || name.toString().contains(part)) continue;
                name.append(part).append(' ');
            }
            return name.isEmpty() ? "未知归属地" : name.toString().trim();
        } catch (Exception e) {
            return "未知归属地";
        }
    }

    @PreDestroy
    public void close() {
        if (lookup == null) return;
        try {
            lookup.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
