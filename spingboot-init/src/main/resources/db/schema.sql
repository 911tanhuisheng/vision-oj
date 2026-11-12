-- =============================================================================
-- 码跃 OJ · 完整数据库（tan 库、业务表、管理员、40 道题、示例竞赛）
--
-- 执行一次即可：
--   mysql -u root -p < schema.sql
--
-- 预置管理员：admin / 123456（需 app.security.pepper 为空，见 application.yml 默认）
-- 已有 tan 库可重复执行：缺表会创建，已列出的缺列会补齐；已有题目不覆盖
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `tan`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `tan`;

-- ---------------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL COMMENT '用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名，唯一',
    `password` VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱，唯一',
    `phone` VARCHAR(32) DEFAULT NULL COMMENT '手机号，唯一',
    `avatar` VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    `sex` TINYINT NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女',
    `birthday` DATETIME DEFAULT NULL COMMENT '生日',
    `location_codes` VARCHAR(255) DEFAULT NULL COMMENT '所在地编码',
    `country` VARCHAR(64) DEFAULT NULL COMMENT '国家',
    `introduction` VARCHAR(1024) DEFAULT NULL COMMENT '个人介绍',
    `website` VARCHAR(255) DEFAULT NULL COMMENT '个人网站',
    `user_role` VARCHAR(32) NOT NULL DEFAULT 'user' COMMENT 'user / admin',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0禁用 1正常',
    `ban_until` DATETIME DEFAULT NULL COMMENT '管理员禁用自动解禁时间',
    `ban_reason` VARCHAR(255) DEFAULT NULL COMMENT '封禁原因',
    `ban_type` VARCHAR(32) DEFAULT NULL COMMENT 'admin 等',
    `comment_ban_until` DATETIME DEFAULT NULL COMMENT '违禁评论解禁时间',
    `comment_ban_reason` VARCHAR(255) DEFAULT NULL COMMENT '违禁评论原因',
    `ai_ban_until` DATETIME DEFAULT NULL COMMENT 'AI 助手违禁解禁时间',
    `ai_ban_reason` VARCHAR(255) DEFAULT NULL COMMENT 'AI 助手违禁原因',
    `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(64) DEFAULT NULL COMMENT '最后登录 IP',
    `last_login_device` VARCHAR(32) DEFAULT NULL COMMENT '最近登录设备类别（浏览器自报）',
    `last_login_os` VARCHAR(64) DEFAULT NULL COMMENT '最近登录操作系统（浏览器自报）',
    `last_login_browser` VARCHAR(64) DEFAULT NULL COMMENT '最近登录浏览器（浏览器自报）',
    `last_login_device_details` VARCHAR(160) DEFAULT NULL COMMENT '浏览器提供的设备概况',
    `points` INT NOT NULL DEFAULT 0 COMMENT '积分',
    `check_in_count` INT NOT NULL DEFAULT 0 COMMENT '累计签到次数',
    `last_check_in_date` DATE DEFAULT NULL COMMENT '最后签到自然日',
    `last_read_announcement_id` BIGINT NOT NULL DEFAULT 0 COMMENT '已读至该公告 id（含）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    UNIQUE KEY `uk_user_email` (`email`),
    UNIQUE KEY `uk_user_phone` (`phone`),
    KEY `idx_user_role_status` (`user_role`, `status`, `is_delete`),
    KEY `idx_user_points` (`points` DESC, `is_delete`),
    KEY `idx_user_check_in` (`last_check_in_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

-- ---------------------------------------------------------------------------
-- 签到历史（日历热力图）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_check_in_log` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户 id',
    `check_in_date` DATE NOT NULL COMMENT '签到自然日',
    `points_awarded` INT NOT NULL DEFAULT 100 COMMENT '当日获得积分',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_check_in_date` (`user_id`, `check_in_date`),
    KEY `idx_check_in_user_date` (`user_id`, `check_in_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到记录';

-- ---------------------------------------------------------------------------
-- 题目 & 提交
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `question` (
    `id` BIGINT NOT NULL COMMENT '题目 id',
    `title` VARCHAR(255) NOT NULL COMMENT '标题',
    `content` MEDIUMTEXT COMMENT '题面',
      `question_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / IMAGE_*',
    `image_url` VARCHAR(1024) DEFAULT NULL COMMENT '图像题题图 URL',
    `vision_model_key` VARCHAR(50) NOT NULL DEFAULT 'YOLO_GENERAL' COMMENT '图像识别模型标识',
    `generation_source` VARCHAR(32) DEFAULT NULL COMMENT '题图来源',
    `generation_model` VARCHAR(128) DEFAULT NULL COMMENT '生图模型标识',
    `count_tolerance` INT NOT NULL DEFAULT 0 COMMENT '目标计数单类允许误差',
    `tags` JSON DEFAULT NULL COMMENT '标签 JSON 数组',
    `answer` MEDIUMTEXT COMMENT '参考实现或题解',
    `submit_num` INT NOT NULL DEFAULT 0 COMMENT '提交数',
    `accepted_num` INT NOT NULL DEFAULT 0 COMMENT '通过数',
    `judge_case` JSON DEFAULT NULL COMMENT '判题用例',
    `judge_config` JSON DEFAULT NULL COMMENT '时间/内存限制等',
    `thumb_num` INT NOT NULL DEFAULT 0,
    `favour_num` INT NOT NULL DEFAULT 0,
    `user_id` BIGINT NOT NULL COMMENT '创建者',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_question_user` (`user_id`, `is_delete`),
    KEY `idx_question_title` (`title`(64)),
    KEY `idx_question_stats` (`is_delete`, `accepted_num` DESC, `submit_num` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目';

CREATE TABLE IF NOT EXISTS `question_submit` (
    `id` BIGINT NOT NULL COMMENT '提交 id',
    `language` VARCHAR(32) NOT NULL COMMENT '编程语言',
    `code` MEDIUMTEXT NOT NULL COMMENT '提交代码',
    `judge_info` JSON DEFAULT NULL COMMENT '判题详情',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待判 1判题中 2成功 3失败',
    `question_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `contest_id` BIGINT DEFAULT NULL COMMENT '比赛提交',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_qs_question` (`question_id`, `is_delete`),
    KEY `idx_qs_user_time` (`user_id`, `create_time` DESC),
    KEY `idx_qs_user_question` (`user_id`, `question_id`, `id`),
    KEY `idx_qs_status` (`status`),
    KEY `idx_qs_contest` (`contest_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码提交';

-- ---------------------------------------------------------------------------
-- 竞赛
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `contest` (
    `id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `description` TEXT,
    `start_time` DATETIME NOT NULL,
    `end_time` DATETIME NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_contest_window` (`is_delete`, `start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞赛';

CREATE TABLE IF NOT EXISTS `contest_user` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_contest_user` (`contest_id`, `user_id`),
    KEY `idx_cu_user` (`user_id`, `is_delete`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞赛报名';

CREATE TABLE IF NOT EXISTS `contest_question` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `question_id` BIGINT NOT NULL,
    `full_score` INT NOT NULL DEFAULT 100,
    `sort_order` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_contest_question` (`contest_id`, `question_id`),
    KEY `idx_cq_contest_sort` (`contest_id`, `sort_order`, `is_delete`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞赛题目';

CREATE TABLE IF NOT EXISTS `contest_rank` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `user_name` VARCHAR(128) DEFAULT NULL COMMENT '展示名快照',
    `total_score` INT NOT NULL DEFAULT 0,
    `total_time` BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时 ms',
    `total_memory` BIGINT NOT NULL DEFAULT 0,
    `game_detail` MEDIUMTEXT COMMENT '每题最优 JSON',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_contest_rank_user` (`contest_id`, `user_id`),
    KEY `idx_cr_contest_score` (`contest_id`, `total_score` DESC, `total_time` ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞赛榜单';

-- ---------------------------------------------------------------------------
-- 博客
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_post` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(256) NOT NULL,
    `slug` VARCHAR(192) NOT NULL,
    `summary` VARCHAR(512) DEFAULT NULL,
    `content` MEDIUMTEXT NOT NULL,
    `cover_url` VARCHAR(1024) DEFAULT NULL,
    `tags` JSON DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布',
    `view_count` INT NOT NULL DEFAULT 0,
    `like_count` INT NOT NULL DEFAULT 0,
    `published_at` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_post_slug` (`slug`),
    KEY `idx_blog_post_feed` (`status`, `published_at` DESC, `is_delete`),
    KEY `idx_blog_post_user` (`user_id`, `status`, `is_delete`),
    KEY `idx_blog_post_title` (`title`(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客文章';

CREATE TABLE IF NOT EXISTS `blog_post_like` (
    `id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_like_post_user` (`post_id`, `user_id`),
    KEY `idx_blog_like_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客点赞';

CREATE TABLE IF NOT EXISTS `blog_comment` (
    `id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `parent_id` BIGINT DEFAULT NULL,
    `content` VARCHAR(2000) NOT NULL,
    `like_count` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_blog_cmt_thread` (`post_id`, `parent_id`, `create_time`),
    KEY `idx_blog_cmt_user` (`user_id`, `is_delete`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客评论';

CREATE TABLE IF NOT EXISTS `blog_comment_like` (
    `id` BIGINT NOT NULL,
    `comment_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_cmt_like` (`comment_id`, `user_id`),
    KEY `idx_blog_cmt_like_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论点赞';

-- ---------------------------------------------------------------------------
-- 站内通知 & 全站公告
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_inapp_notification` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `type` TINYINT NOT NULL COMMENT '1评论 2回复 3报名 4开赛提醒等',
    `title` VARCHAR(256) NOT NULL,
    `body` VARCHAR(512) DEFAULT NULL,
    `link_kind` VARCHAR(32) DEFAULT NULL,
    `link_ref` VARCHAR(64) DEFAULT NULL,
    `biz_key` VARCHAR(160) DEFAULT NULL COMMENT '幂等键',
    `read_at` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uin_user_biz` (`user_id`, `biz_key`),
    KEY `idx_uin_inbox` (`user_id`, `is_delete`, `read_at`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知';

CREATE TABLE IF NOT EXISTS `user_ai_provider_config` (
    `user_id` BIGINT NOT NULL,
    `provider_code` VARCHAR(20) NOT NULL,
    `model_id` VARCHAR(100) NOT NULL,
    `key_cipher` TEXT NOT NULL,
    `key_last4` VARCHAR(4) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户个人大模型凭据（AES-GCM密文）';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不保存提示词和代码的模型调用统计';

CREATE TABLE IF NOT EXISTS `site_announcement` (
    `id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `content` MEDIUMTEXT,
    `publisher_id` BIGINT DEFAULT NULL,
    `published_at` DATETIME DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1已发布',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_sa_list` (`status`, `is_delete`, `published_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全站公告';

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 旧库兼容：user 表缺列时自动补齐（新库已含下列字段，执行无影响）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `mayue_add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `mayue_add_column_if_missing`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL `mayue_add_column_if_missing`('user', 'ban_until',
    '`ban_until` DATETIME DEFAULT NULL COMMENT ''管理员禁用解禁时间'' AFTER `status`');
CALL `mayue_add_column_if_missing`('user', 'ban_reason',
    '`ban_reason` VARCHAR(255) DEFAULT NULL COMMENT ''封禁原因'' AFTER `ban_until`');
CALL `mayue_add_column_if_missing`('user', 'ban_type',
    '`ban_type` VARCHAR(32) DEFAULT NULL COMMENT ''封禁类型'' AFTER `ban_reason`');
CALL `mayue_add_column_if_missing`('user', 'comment_ban_until',
    '`comment_ban_until` DATETIME DEFAULT NULL COMMENT ''违禁评论解禁'' AFTER `ban_type`');
CALL `mayue_add_column_if_missing`('user', 'comment_ban_reason',
    '`comment_ban_reason` VARCHAR(255) DEFAULT NULL COMMENT ''违禁评论原因'' AFTER `comment_ban_until`');
CALL `mayue_add_column_if_missing`('user', 'ai_ban_until',
    '`ai_ban_until` DATETIME DEFAULT NULL COMMENT ''AI违禁解禁'' AFTER `comment_ban_reason`');
CALL `mayue_add_column_if_missing`('user', 'ai_ban_reason',
    '`ai_ban_reason` VARCHAR(255) DEFAULT NULL COMMENT ''AI违禁原因'' AFTER `ai_ban_until`');
CALL `mayue_add_column_if_missing`('user', 'points',
    '`points` INT NOT NULL DEFAULT 0 COMMENT ''积分'' AFTER `last_login_ip`');
CALL `mayue_add_column_if_missing`('user', 'last_login_device',
    '`last_login_device` VARCHAR(32) DEFAULT NULL COMMENT ''最近登录设备类别'' AFTER `last_login_ip`');
CALL `mayue_add_column_if_missing`('user', 'last_login_os',
    '`last_login_os` VARCHAR(64) DEFAULT NULL COMMENT ''最近登录操作系统'' AFTER `last_login_device`');
CALL `mayue_add_column_if_missing`('user', 'last_login_browser',
    '`last_login_browser` VARCHAR(64) DEFAULT NULL COMMENT ''最近登录浏览器'' AFTER `last_login_os`');
CALL `mayue_add_column_if_missing`('user', 'last_login_device_details',
    '`last_login_device_details` VARCHAR(160) DEFAULT NULL COMMENT ''浏览器设备概况'' AFTER `last_login_browser`');
CALL `mayue_add_column_if_missing`('user', 'check_in_count',
    '`check_in_count` INT NOT NULL DEFAULT 0 COMMENT ''累计签到'' AFTER `points`');
CALL `mayue_add_column_if_missing`('user', 'last_check_in_date',
    '`last_check_in_date` DATE DEFAULT NULL COMMENT ''最后签到日'' AFTER `check_in_count`');
CALL `mayue_add_column_if_missing`('user', 'last_read_announcement_id',
    '`last_read_announcement_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''公告已读游标'' AFTER `last_check_in_date`');
CALL `mayue_add_column_if_missing`('question', 'question_type',
    '`question_type` VARCHAR(32) NOT NULL DEFAULT ''TEXT'' COMMENT ''TEXT / IMAGE_*'' AFTER `content`');
CALL `mayue_add_column_if_missing`('question', 'image_url',
    '`image_url` VARCHAR(1024) DEFAULT NULL COMMENT ''图像题题图 URL'' AFTER `question_type`');
CALL `mayue_add_column_if_missing`('question', 'vision_model_key',
    '`vision_model_key` VARCHAR(50) NOT NULL DEFAULT ''YOLO_GENERAL'' COMMENT ''图像识别模型标识'' AFTER `image_url`');
CALL `mayue_add_column_if_missing`('question', 'generation_source',
    '`generation_source` VARCHAR(32) DEFAULT NULL COMMENT ''题图来源'' AFTER `vision_model_key`');
CALL `mayue_add_column_if_missing`('question', 'generation_model',
    '`generation_model` VARCHAR(128) DEFAULT NULL COMMENT ''生图模型标识'' AFTER `generation_source`');
CALL `mayue_add_column_if_missing`('question', 'count_tolerance',
    '`count_tolerance` INT NOT NULL DEFAULT 0 COMMENT ''目标计数单类允许误差'' AFTER `vision_model_key`');

DROP PROCEDURE IF EXISTS `mayue_add_column_if_missing`;

-- ---------------------------------------------------------------------------
-- 初始数据：管理员 admin / 123456
-- 密码 = BCrypt(12, sha256Hex('123456' + pepper))，pepper 为空时有效
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `user` (
    `id`, `username`, `password`, `email`, `nickname`,
    `sex`, `user_role`, `status`, `points`, `check_in_count`,
    `last_read_announcement_id`, `create_time`, `update_time`, `is_delete`
) VALUES (
    1999990000000000001,
    'admin',
    '$2b$12$G9mY/PieJFSciBfogd1dWOXTo4NOk8LdMhlwGceXVw3qrbDvUCJje',
    'admin@myoj.local',
    '管理员',
    0, 'admin', 1, 0, 0, 0, NOW(), NOW(), 0
);

-- ---------------------------------------------------------------------------
-- 初始数据：15 道常用入门题（后文另有 25 道扩展题）
-- judge_case：input 为程序 stdin；output 与沙箱 stdout trim 后严格比对
-- judge_config：时间 ms / 内存 KB / 栈 KB
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `question` (
    `id`, `title`, `content`, `tags`, `answer`,
    `submit_num`, `accepted_num`, `judge_case`, `judge_config`,
    `thumb_num`, `favour_num`, `user_id`, `create_time`, `update_time`, `is_delete`
) VALUES
(
    200001, 'A+B 问题',
    '给定两个整数 A 和 B（|A|,|B| ≤ 10^4），计算 A+B。\n\n**输入**：一行两个整数，空格分隔。\n**输出**：一个整数，表示和。',
    '["入门","基础语法","模拟"]', NULL, 0, 0,
    '[{"input":"1 2","output":"3"},{"input":"100 200","output":"300"},{"input":"-7 15","output":"8"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200002, 'Hello World',
    '输出一行 `Hello World`。\n\n**输入**：无（或空行）。\n**输出**：`Hello World`',
    '["入门","基础语法","输出"]', NULL, 0, 0,
    '[{"input":"","output":"Hello World"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200003, '判断奇偶',
    '判断整数 N 的奇偶性。\n\n**输入**：一行整数 N（|N| ≤ 10^9）。\n**输出**：`even` 或 `odd`（小写）。',
    '["入门","基础语法","分支"]', NULL, 0, 0,
    '[{"input":"4","output":"even"},{"input":"7","output":"odd"},{"input":"0","output":"even"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200004, '两数较大',
    '输入两个整数，输出较大者（相等输出任意一个）。\n\n**输入**：一行两个整数。\n**输出**：较大值。',
    '["入门","基础语法","分支"]', NULL, 0, 0,
    '[{"input":"3 7","output":"7"},{"input":"-5 -2","output":"-2"},{"input":"9 9","output":"9"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200005, '逆序输出三整数',
    '输入三个整数，按逆序输出（空格分隔，行末无多余空格）。\n\n**输入**：一行三个整数。\n**输出**：逆序一行。',
    '["入门","基础语法","模拟"]', NULL, 0, 0,
    '[{"input":"1 2 3","output":"3 2 1"},{"input":"10 20 30","output":"30 20 10"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200006, '1 到 n 求和',
    '计算 1+2+…+n。\n\n**输入**：正整数 n（1 ≤ n ≤ 10^6）。\n**输出**：和。',
    '["入门","数学","循环"]', NULL, 0, 0,
    '[{"input":"5","output":"15"},{"input":"1","output":"1"},{"input":"100","output":"5050"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200007, '阶乘',
    '计算 n!（n ≤ 12，结果在 long 范围内）。\n\n**输入**：非负整数 n。\n**输出**：n!。',
    '["入门","数学","循环"]', NULL, 0, 0,
    '[{"input":"5","output":"120"},{"input":"0","output":"1"},{"input":"10","output":"3628800"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200008, '斐波那契第 n 项',
    '斐波那契：F(1)=1, F(2)=1, F(n)=F(n-1)+F(n-2)。\n\n**输入**：正整数 n（1 ≤ n ≤ 30）。\n**输出**：F(n)。',
    '["简单","动态规划","递推"]', NULL, 0, 0,
    '[{"input":"1","output":"1"},{"input":"7","output":"13"},{"input":"10","output":"55"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200009, '闰年判断',
    '判断年份是否为闰年（公历规则）。\n\n**输入**：年份 y（1582 ≤ y ≤ 9999）。\n**输出**：`yes` 或 `no`（小写）。',
    '["入门","数学","分支"]', NULL, 0, 0,
    '[{"input":"2000","output":"yes"},{"input":"1900","output":"no"},{"input":"2024","output":"yes"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200010, '最大公约数',
    '求两个正整数的最大公约数。\n\n**输入**：两个正整数 a、b（≤ 10^9）。\n**输出**：gcd(a,b)。',
    '["简单","数学","欧几里得算法"]', NULL, 0, 0,
    '[{"input":"12 18","output":"6"},{"input":"7 13","output":"1"},{"input":"100 25","output":"25"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200011, '判断素数',
    '判断正整数 n 是否为素数（n ≥ 2 时定义；n=1 输出 no）。\n\n**输入**：正整数 n（≤ 10^6）。\n**输出**：`yes` 或 `no`。',
    '["简单","数学","素数"]', NULL, 0, 0,
    '[{"input":"17","output":"yes"},{"input":"18","output":"no"},{"input":"2","output":"yes"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200012, '水仙花数',
    '判断三位正整数是否为水仙花数（各位立方和等于自身）。\n\n**输入**：三位正整数 n。\n**输出**：`yes` 或 `no`。',
    '["简单","数学","枚举"]', NULL, 0, 0,
    '[{"input":"153","output":"yes"},{"input":"123","output":"no"},{"input":"370","output":"yes"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200013, '反转整数',
    '将整数 n 的各位数字反转后输出（符号保留；反转后末尾 0 可省略，如 1230→321）。\n\n**输入**：整数 n（|n| ≤ 10^9）。\n**输出**：反转结果。',
    '["入门","基础语法","模拟"]', NULL, 0, 0,
    '[{"input":"1234","output":"4321"},{"input":"-1200","output":"-21"},{"input":"0","output":"0"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200014, 'n 个整数最大值',
    '第一行整数 n，第二行 n 个整数，求最大值。\n\n**输入**：如上（1 ≤ n ≤ 10^4）。\n**输出**：最大值。',
    '["入门","数组","遍历"]', NULL, 0, 0,
    '[{"input":"5\\n3 1 4 1 5","output":"5"},{"input":"1\\n-100","output":"-100"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
),
(
    200015, '能否构成三角形',
    '判断三条正整数边长能否构成三角形（任意两边之和大于第三边）。\n\n**输入**：三个正整数 a b c。\n**输出**：`yes` 或 `no`。',
    '["入门","数学","分支"]', NULL, 0, 0,
    '[{"input":"3 4 5","output":"yes"},{"input":"1 2 3","output":"no"},{"input":"5 5 5","output":"yes"}]',
    '{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',
    0, 0, 1999990000000000001, NOW(), NOW(), 0
);

-- ---------------------------------------------------------------------------
-- 初始数据：示例竞赛（题库有题时自动挂第一道题）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `contest` (
    `id`, `title`, `description`, `start_time`, `end_time`,
    `create_time`, `update_time`, `is_delete`
) VALUES (
    190001,
    '示例练习赛',
    '比赛列表 → 报名 → 赛题做题 → 提交后刷新榜单。',
    DATE_SUB(NOW(), INTERVAL 1 DAY),
    DATE_ADD(NOW(), INTERVAL 60 DAY),
    NOW(), NOW(), 0
);

INSERT IGNORE INTO `contest_question` (
    `id`, `contest_id`, `question_id`, `full_score`, `sort_order`,
    `create_time`, `update_time`, `is_delete`
)
SELECT 190002, 190001, q.`id`, 100, 0, NOW(), NOW(), 0
FROM `question` q
WHERE q.`is_delete` = 0
ORDER BY q.`id` ASC
LIMIT 1;

-- ---------------------------------------------------------------------------
-- 扩展基础算法题（25 题；与入门题一并初始化）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `question` (
  `id`,`title`,`content`,`question_type`,`tags`,`answer`,`submit_num`,`accepted_num`,
  `judge_case`,`judge_config`,`thumb_num`,`favour_num`,`user_id`,`create_time`,`update_time`,`is_delete`
) VALUES
(200101,'统计偶数个数','给定 n 个整数，统计其中偶数的个数。\n\n**输入格式**：第一行输入 n；第二行输入 n 个整数。\n**输出格式**：输出偶数的个数。\n**数据范围**：1 ≤ n ≤ 10^5。','TEXT','["入门","数组","遍历"]',NULL,0,0,
 '[{"input":"5\\n1 2 3 4 6","output":"3","sample":true},{"input":"4\\n-2 -1 0 7","output":"2","sample":false},{"input":"1\\n9","output":"0","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200102,'数组元素求和','给定 n 个整数，输出它们的总和。\n\n**输入格式**：第一行输入 n；第二行输入 n 个整数。\n**输出格式**：输出总和。\n**数据范围**：1 ≤ n ≤ 10^5，元素绝对值不超过 10^9。','TEXT','["入门","数组","前缀和"]',NULL,0,0,
 '[{"input":"4\\n1 2 3 4","output":"10","sample":true},{"input":"3\\n-5 10 -2","output":"3","sample":false},{"input":"1\\n0","output":"0","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200103,'去重后的第二大值','给定 n 个整数，输出不同数值中的第二大值；若不存在第二大的不同值，输出 `NONE`。\n\n**输入格式**：第一行 n，第二行 n 个整数。\n**输出格式**：第二大不同值或 `NONE`。','TEXT','["简单","数组","遍历"]',NULL,0,0,
 '[{"input":"5\\n1 5 3 5 2","output":"3","sample":true},{"input":"3\\n7 7 7","output":"NONE","sample":false},{"input":"4\\n-1 -9 -3 -1","output":"-3","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200104,'升序排列','将 n 个整数按非递减顺序输出。\n\n**输入格式**：第一行 n，第二行 n 个整数。\n**输出格式**：升序结果，数字之间以一个空格分隔。','TEXT','["简单","排序","数组"]',NULL,0,0,
 '[{"input":"5\\n5 1 4 2 3","output":"1 2 3 4 5","sample":true},{"input":"4\\n3 -1 3 0","output":"-1 0 3 3","sample":false},{"input":"1\\n42","output":"42","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200105,'有序数组查找','在非递减整数数组中查找目标值 x，输出第一次出现的下标（从 0 开始）；不存在输出 -1。\n\n**输入格式**：第一行 n 和 x；第二行 n 个已排序整数。\n**输出格式**：下标或 -1。','TEXT','["简单","二分查找","数组"]',NULL,0,0,
 '[{"input":"6 4\\n1 2 4 4 4 9","output":"2","sample":true},{"input":"4 5\\n1 3 7 9","output":"-1","sample":false},{"input":"1 -2\\n-2","output":"0","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200106,'回文字符串','给定仅含小写字母的字符串，判断它正读和反读是否相同。\n\n**输入格式**：一行字符串。\n**输出格式**：是回文输出 `yes`，否则输出 `no`。','TEXT','["入门","字符串","双指针"]',NULL,0,0,
 '[{"input":"level","output":"yes","sample":true},{"input":"coding","output":"no","sample":false},{"input":"a","output":"yes","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200107,'元音字母计数','统计一行英文字符串中 a、e、i、o、u（不区分大小写）的出现次数。\n\n**输入格式**：一行不含换行的字符串。\n**输出格式**：一个整数。','TEXT','["入门","字符串","计数"]',NULL,0,0,
 '[{"input":"Hello Algorithm","output":"5","sample":true},{"input":"xyz","output":"0","sample":false},{"input":"AEIOU","output":"5","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200108,'括号序列合法性','给定只含 `(`、`)`、`[`、`]`、`{`、`}` 的字符串，判断括号是否匹配。\n\n**输入格式**：一行括号字符串。\n**输出格式**：合法输出 `yes`，否则输出 `no`。','TEXT','["中等","栈","字符串"]',NULL,0,0,
 '[{"input":"{[()]}","output":"yes","sample":true},{"input":"([)]","output":"no","sample":false},{"input":"(()","output":"no","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200109,'区间和查询','给定数组和若干查询，每个查询给出 l、r，输出闭区间 [l,r] 的元素和。下标从 1 开始。\n\n**输入格式**：第一行 n、q；第二行 n 个整数；接下来 q 行，每行 l、r。\n**输出格式**：每个查询一行。','TEXT','["简单","前缀和","数组"]',NULL,0,0,
 '[{"input":"5 3\\n1 2 3 4 5\\n1 3\\n2 5\\n4 4","output":"6\\n14\\n4","sample":true},{"input":"3 2\\n-1 0 5\\n1 1\\n1 3","output":"-1\\n4","sample":false},{"input":"1 1\\n8\\n1 1","output":"8","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200110,'两数之和下标','给定 n 个整数和目标值 t，保证恰好存在一组不同下标 i<j，使 a[i]+a[j]=t。输出 i 和 j（下标从 0 开始）。\n\n**输入格式**：第一行 n、t；第二行 n 个整数。\n**输出格式**：i 和 j。','TEXT','["简单","哈希表","数组"]',NULL,0,0,
 '[{"input":"4 9\\n2 7 11 15","output":"0 1","sample":true},{"input":"5 0\\n-3 4 3 90 1","output":"0 2","sample":false},{"input":"2 8\\n4 4","output":"0 1","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200111,'最大连续子段和','给定 n 个整数，选择一个非空连续子段，使其元素和最大，输出该最大和。\n\n**输入格式**：第一行 n；第二行 n 个整数。\n**输出格式**：最大连续子段和。','TEXT','["中等","动态规划","数组"]',NULL,0,0,
 '[{"input":"9\\n-2 1 -3 4 -1 2 1 -5 4","output":"6","sample":true},{"input":"3\\n-5 -2 -7","output":"-2","sample":false},{"input":"4\\n1 2 3 4","output":"10","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200112,'最少硬币数','给定硬币面额 1、3、4，求凑出金额 n 所需的最少硬币数。\n\n**输入格式**：一个非负整数 n。\n**输出格式**：最少硬币数。\n**数据范围**：0 ≤ n ≤ 10^5。','TEXT','["中等","动态规划","完全背包"]',NULL,0,0,
 '[{"input":"6","output":"2","sample":true},{"input":"2","output":"2","sample":false},{"input":"10","output":"3","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200113,'网格路径计数','从 n×m 网格左上角出发，每次只能向右或向下走一步，到达右下角有多少条不同路径。答案保证在 64 位整数范围内。\n\n**输入格式**：两个正整数 n、m。\n**输出格式**：路径数量。','TEXT','["中等","动态规划","组合数学"]',NULL,0,0,
 '[{"input":"3 3","output":"6","sample":true},{"input":"1 5","output":"1","sample":false},{"input":"4 2","output":"4","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200114,'最长公共子序列长度','给定两个只含小写字母的字符串，输出它们最长公共子序列的长度。\n\n**输入格式**：两行字符串。\n**输出格式**：一个整数。','TEXT','["中等","动态规划","字符串"]',NULL,0,0,
 '[{"input":"abcde\\nace","output":"3","sample":true},{"input":"abc\\ndef","output":"0","sample":false},{"input":"aaaa\\naa","output":"2","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200115,'迷宫最短步数','给定 n×m 网格，`0` 表示可走，`1` 表示障碍。从左上角走到右下角，每次可向上下左右移动一格。输出最少步数，无法到达输出 -1。起点和终点保证可走。\n\n**输入格式**：第一行 n、m；之后 n 行，每行 m 个 0 或 1。\n**输出格式**：最少步数或 -1。','TEXT','["中等","BFS","图论"]',NULL,0,0,
 '[{"input":"3 3\\n0 0 0\\n1 1 0\\n0 0 0","output":"4","sample":true},{"input":"2 2\\n0 1\\n1 0","output":"-1","sample":false},{"input":"1 1\\n0","output":"0","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200116,'最短路径距离','给定 n 个点、m 条无向非负边，求点 1 到点 n 的最短距离；无法到达输出 -1。\n\n**输入格式**：第一行 n、m；接下来 m 行每行 u、v、w。\n**输出格式**：最短距离或 -1。','TEXT','["中等","Dijkstra","图论"]',NULL,0,0,
 '[{"input":"4 4\\n1 2 2\\n2 4 3\\n1 3 10\\n3 4 1","output":"5","sample":true},{"input":"3 1\\n1 2 5","output":"-1","sample":false},{"input":"2 1\\n1 2 7","output":"7","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200117,'课程依赖是否有环','给定 n 个任务和 m 条有向依赖 u→v，判断图中是否存在有向环。\n\n**输入格式**：第一行 n、m；接下来 m 行 u、v。\n**输出格式**：有环输出 `yes`，无环输出 `no`。','TEXT','["中等","拓扑排序","图论"]',NULL,0,0,
 '[{"input":"3 3\\n1 2\\n2 3\\n3 1","output":"yes","sample":true},{"input":"4 3\\n1 2\\n1 3\\n3 4","output":"no","sample":false},{"input":"1 0","output":"no","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200118,'连通分量数量','给定 n 个点和 m 条无向边，输出图的连通分量数量。\n\n**输入格式**：第一行 n、m；接下来 m 行 u、v。\n**输出格式**：连通分量数量。','TEXT','["简单","并查集","图论"]',NULL,0,0,
 '[{"input":"5 2\\n1 2\\n4 5","output":"3","sample":true},{"input":"3 3\\n1 2\\n2 3\\n1 3","output":"1","sample":false},{"input":"4 0","output":"4","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200119,'01 背包最大价值','有 n 件物品，第 i 件重量为 w_i、价值为 v_i。背包容量为 C，每件物品最多选一次，输出最大总价值。\n\n**输入格式**：第一行 n、C；接下来 n 行每行 w_i、v_i。\n**输出格式**：最大价值。','TEXT','["中等","动态规划","背包"]',NULL,0,0,
 '[{"input":"3 4\\n2 3\\n1 2\\n3 4","output":"6","sample":true},{"input":"2 3\\n2 5\\n3 6","output":"6","sample":false},{"input":"1 1\\n2 9","output":"0","sample":false}]','{"timeLimit":2000,"memoryLimit":262144,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200120,'固定长度最大区间和','给定 n 个整数和长度 k，输出长度恰好为 k 的连续子数组的最大元素和。\n\n**输入格式**：第一行 n、k；第二行 n 个整数。\n**输出格式**：最大区间和。','TEXT','["简单","滑动窗口","数组"]',NULL,0,0,
 '[{"input":"6 3\\n2 1 5 1 3 2","output":"9","sample":true},{"input":"4 1\\n-2 -1 -5 -3","output":"-1","sample":false},{"input":"3 3\\n1 2 3","output":"6","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200121,'字符串模式首次位置','给定文本串 s 和模式串 p，输出 p 在 s 中第一次出现的起始下标（从 0 开始）；不存在输出 -1。\n\n**输入格式**：第一行 s，第二行 p。\n**输出格式**：下标或 -1。','TEXT','["中等","字符串","KMP"]',NULL,0,0,
 '[{"input":"ababcabc\\nabc","output":"2","sample":true},{"input":"aaaaa\\nbba","output":"-1","sample":false},{"input":"hello\\nll","output":"2","sample":false}]','{"timeLimit":2000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200122,'快速幂取模','计算 a 的 b 次方对 m 取模，即 a^b mod m。\n\n**输入格式**：三个非负整数 a、b、m，m≥1。\n**输出格式**：结果。','TEXT','["中等","数学","快速幂"]',NULL,0,0,
 '[{"input":"2 10 1000","output":"24","sample":true},{"input":"7 0 13","output":"1","sample":false},{"input":"10 5 7","output":"5","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200123,'逆波兰表达式求值','给定一个合法的逆波兰表达式，操作数为整数，运算符为 `+ - * /`；除法向 0 截断。输出表达式值。\n\n**输入格式**：一行 token，以空格分隔。\n**输出格式**：表达式值。','TEXT','["中等","栈","表达式求值"]',NULL,0,0,
 '[{"input":"2 1 + 3 *","output":"9","sample":true},{"input":"4 13 5 / +","output":"6","sample":false},{"input":"10 6 - 2 *","output":"8","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200124,'矩阵顺时针旋转','给定 n×n 整数矩阵，将其顺时针旋转 90 度后输出。\n\n**输入格式**：第一行 n；之后 n 行，每行 n 个整数。\n**输出格式**：旋转后的矩阵，每行数字以空格分隔。','TEXT','["中等","矩阵","模拟"]',NULL,0,0,
 '[{"input":"2\\n1 2\\n3 4","output":"3 1\\n4 2","sample":true},{"input":"1\\n9","output":"9","sample":false},{"input":"3\\n1 2 3\\n4 5 6\\n7 8 9","output":"7 4 1\\n8 5 2\\n9 6 3","sample":false}]','{"timeLimit":1000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0),
(200125,'合并区间数量','给定 n 个闭区间 [l,r]，将有交集或端点相接的区间合并，输出合并后的区间数量。\n\n**输入格式**：第一行 n；接下来 n 行 l、r。\n**输出格式**：合并后区间数量。','TEXT','["中等","贪心","排序"]',NULL,0,0,
 '[{"input":"4\\n1 3\\n2 4\\n6 7\\n7 8","output":"2","sample":true},{"input":"3\\n1 1\\n3 3\\n5 5","output":"3","sample":false},{"input":"2\\n-2 5\\n0 1","output":"1","sample":false}]','{"timeLimit":2000,"memoryLimit":131072,"stackLimit":8192}',0,0,1999990000000000001,NOW(),NOW(),0);
