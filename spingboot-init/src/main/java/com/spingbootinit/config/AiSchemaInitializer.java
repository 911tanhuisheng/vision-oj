package com.spingbootinit.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 老数据库启动时只补建个人模型配置表，不执行完整 schema.sql。 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AiSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner initializeAiModelTables() {
        return args -> {
            try {
                jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS `user_ai_provider_config` (
                            `user_id` BIGINT NOT NULL,
                            `provider_code` VARCHAR(20) NOT NULL,
                            `model_id` VARCHAR(100) NOT NULL,
                            `key_cipher` TEXT NOT NULL,
                            `key_last4` VARCHAR(4) NOT NULL,
                            `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            PRIMARY KEY (`user_id`, `provider_code`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                          COMMENT='用户个人大模型凭据（AES-GCM密文）'
                        """);
                jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS `ai_usage_log` (
                            `id` BIGINT NOT NULL AUTO_INCREMENT,
                            `user_id` BIGINT NOT NULL,
                            `provider_code` VARCHAR(20) NOT NULL,
                            `model_id` VARCHAR(100) NOT NULL,
                            `feature` VARCHAR(20) NOT NULL,
                            `duration_ms` BIGINT NOT NULL,
                            `success` TINYINT NOT NULL,
                            `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (`id`),
                            KEY `idx_ai_usage_user_time` (`user_id`, `create_time`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                          COMMENT='不保存提示词和代码的模型调用统计'
                        """);
            } catch (Exception e) {
                // 站点默认模型和既有功能继续可用；可用完整 schema.sql 补建缺失表。
                log.warn("AI model table initialization failed; check database permissions or apply db/schema.sql. errorType={}",
                        e.getClass().getSimpleName());
            }
        };
    }
}
