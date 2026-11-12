package com.spingbootinit.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 兼容已经创建的 user 表；新库由 schema.sql 创建这些列。 */
@Component
@RequiredArgsConstructor
public class UserLoginMetadataMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        addIfMissing("last_login_device", "VARCHAR(32) DEFAULT NULL COMMENT '最近登录设备类别'");
        addIfMissing("last_login_os", "VARCHAR(64) DEFAULT NULL COMMENT '最近登录操作系统'");
        addIfMissing("last_login_browser", "VARCHAR(64) DEFAULT NULL COMMENT '最近登录浏览器'");
        addIfMissing("last_login_device_details", "VARCHAR(160) DEFAULT NULL COMMENT '浏览器提供的设备概况'");
    }

    private void addIfMissing(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = 'user' AND COLUMN_NAME = ?", Integer.class, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN `" + column + "` " + definition);
        }
    }
}
