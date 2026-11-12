package com.spingbootinit.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps existing development databases compatible with image model routing.
 * schema.sql handles fresh installations; this runner handles an already-created question table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionVisionSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'vision_model_key'",
                Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE question ADD COLUMN vision_model_key VARCHAR(50) " +
                    "NOT NULL DEFAULT 'YOLO_GENERAL' COMMENT '图像识别模型键' AFTER image_url");
            log.info("Database migration completed: question.vision_model_key added");
        }
        addColumnIfMissing("generation_source", "ALTER TABLE question ADD COLUMN generation_source VARCHAR(32) DEFAULT NULL AFTER vision_model_key");
        addColumnIfMissing("generation_model", "ALTER TABLE question ADD COLUMN generation_model VARCHAR(128) DEFAULT NULL AFTER generation_source");
    }

    private void addColumnIfMissing(String name, String ddl) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = ?",
                Integer.class, name);
        if (existing != null && existing == 0) jdbcTemplate.execute(ddl);
    }
}
