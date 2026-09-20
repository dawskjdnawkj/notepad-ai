-- ============================================================
-- 云记事本（CloudNotepad）初始化建表脚本
-- 数据库 : cloud_notepad
-- 引擎   : InnoDB
-- 字符集 : utf8mb4 / utf8mb4_general_ci
-- 要求   : MySQL 8.0+（全文索引依赖 ngram 解析器）
-- 执行   : mysql -uroot -p < init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS cloud_notepad
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE cloud_notepad;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
CREATE TABLE `user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(32)     NOT NULL                COMMENT '用户名，唯一',
    `password`    VARCHAR(100)    NOT NULL                COMMENT '密码（BCrypt 密文）',
    `nickname`    VARCHAR(32)     DEFAULT NULL            COMMENT '昵称',
    `avatar`      VARCHAR(255)    DEFAULT NULL            COMMENT '头像地址（预留）',
    `email`       VARCHAR(64)     DEFAULT NULL            COMMENT '邮箱（预留找回密码）',
    `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1 正常 / 0 禁用',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户表';

-- ------------------------------------------------------------
-- 2. 笔记本表
-- ------------------------------------------------------------
CREATE TABLE `notebook` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属用户（逻辑外键 -> user.id）',
    `name`        VARCHAR(50)     NOT NULL                COMMENT '笔记本名称（同一用户下唯一）',
    `is_default`  TINYINT         NOT NULL DEFAULT 0      COMMENT '是否默认笔记本：1 是 / 0 否（默认笔记本不可重命名、不可删除）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '笔记本表';

-- ------------------------------------------------------------
-- 3. 笔记表（核心表）
-- ------------------------------------------------------------
CREATE TABLE `note` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属用户（逻辑外键 -> user.id）',
    `notebook_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属笔记本（逻辑外键 -> notebook.id）',
    `title`       VARCHAR(200)    NOT NULL DEFAULT ''     COMMENT '标题',
    `content`     MEDIUMTEXT      NULL                    COMMENT '正文（富文本 HTML）',
    `content_text` MEDIUMTEXT     NULL                    COMMENT '正文纯文本（搜索用，HTML 剥离后）',
    `pinned`      TINYINT         NOT NULL DEFAULT 0      COMMENT '置顶：0 否 / 1 是',
    `delete_time` DATETIME        DEFAULT NULL            COMMENT '移入回收站时间，30 天自动清空依据',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（日历标记依据）',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后编辑时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '0 正常 / 1 回收站（逻辑删除）',
    PRIMARY KEY (`id`),
    KEY `idx_user_create` (`user_id`, `create_time`),
    KEY `idx_user_update` (`user_id`, `update_time`),
    KEY `idx_user_notebook` (`user_id`, `notebook_id`),
    KEY `idx_user_delete_time` (`user_id`, `delete_time`),
    KEY `idx_notebook_id` (`notebook_id`),
    FULLTEXT KEY `ft_title_content_text` (`title`, `content_text`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '笔记表';

-- ------------------------------------------------------------
-- 4. 标签表
-- ------------------------------------------------------------
CREATE TABLE `tag` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属用户（逻辑外键 -> user.id）',
    `name`        VARCHAR(30)     NOT NULL                COMMENT '标签名称（同一用户下唯一）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '标签表';

-- ------------------------------------------------------------
-- 5. 笔记-标签关联表
-- ------------------------------------------------------------
CREATE TABLE `note_tag` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `note_id`     BIGINT UNSIGNED NOT NULL                COMMENT '笔记ID（逻辑外键 -> note.id）',
    `tag_id`      BIGINT UNSIGNED NOT NULL                COMMENT '标签ID（逻辑外键 -> tag.id）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_note_tag` (`note_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '笔记-标签关联表';

-- ------------------------------------------------------------
-- 6. 提醒表
-- ------------------------------------------------------------
CREATE TABLE `reminder` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属用户（逻辑外键 -> user.id）',
    `note_id`     BIGINT UNSIGNED NOT NULL                COMMENT '被提醒的笔记（逻辑外键 -> note.id）',
    `remind_at`   DATETIME        NOT NULL                COMMENT '提醒时间（由 N 小时/天后换算，预留指定时间）',
    `status`      TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0 待提醒 / 1 已提醒 / 2 已取消',
    `notified_at` DATETIME        DEFAULT NULL            COMMENT '实际生成通知的时间',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_remind` (`status`, `remind_at`),
    KEY `idx_note_id` (`note_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提醒表';

-- ------------------------------------------------------------
-- 7. 通知表
-- ------------------------------------------------------------
CREATE TABLE `notification` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '接收用户（逻辑外键 -> user.id）',
    `type`        TINYINT         NOT NULL DEFAULT 1      COMMENT '通知类型：1 提醒（预留更多类型）',
    `title`       VARCHAR(100)    NOT NULL                COMMENT '通知标题',
    `content`     VARCHAR(500)    NOT NULL                COMMENT '通知内容',
    `note_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联笔记（点击跳转；笔记被彻底删除后跳转失效）',
    `is_read`     TINYINT         NOT NULL DEFAULT 0      COMMENT '是否已读：0 未读 / 1 已读',
    `read_time`   DATETIME        DEFAULT NULL            COMMENT '阅读时间',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `is_read`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '通知表';

-- ------------------------------------------------------------
-- 8. 笔记图片表
-- ------------------------------------------------------------
CREATE TABLE `note_image` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '上传用户（逻辑外键 -> user.id）',
    `note_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '所属笔记（临时图片为空 -> note.id）',
    `url`           VARCHAR(500)    NOT NULL                COMMENT '访问 URL（相对路径）',
    `file_name`     VARCHAR(255)    NOT NULL                COMMENT '磁盘相对路径',
    `original_name` VARCHAR(255)    DEFAULT NULL            COMMENT '原始文件名',
    `size`          BIGINT          NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_note` (`user_id`, `note_id`),
    KEY `idx_url` (`url`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '笔记图片表';

-- ------------------------------------------------------------
-- 9. AI 助手会话表
-- ------------------------------------------------------------
CREATE TABLE `ai_conversation` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属用户（逻辑外键 -> user.id）',
    `client_id`   VARCHAR(64)     NOT NULL                COMMENT '前端生成的跨设备会话标识',
    `title`       VARCHAR(100)    NOT NULL                COMMENT '会话标题',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_client` (`user_id`, `client_id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 助手会话表';

-- ------------------------------------------------------------
-- 10. AI 助手会话消息表
-- ------------------------------------------------------------
CREATE TABLE `ai_conversation_message` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属会话（逻辑外键 -> ai_conversation.id）',
    `user_id`         BIGINT UNSIGNED NOT NULL                COMMENT '所属用户，用于查询隔离',
    `client_message_id` VARCHAR(64)   NOT NULL                COMMENT '前端生成的稳定消息标识',
    `sequence_no`     INT UNSIGNED    NOT NULL                COMMENT '消息在会话中的顺序，从 0 开始',
    `role`            VARCHAR(16)     NOT NULL                COMMENT '角色：user / assistant',
    `content`         MEDIUMTEXT      NOT NULL                COMMENT '消息正文',
    `question`        VARCHAR(500)    DEFAULT NULL            COMMENT '助手消息对应的原始问题',
    `sources_json`    MEDIUMTEXT      NULL                    COMMENT '回答引用片段 JSON',
    `scope_type`      VARCHAR(16)     NULL                    COMMENT '检索范围：all / notebook / note',
    `scope_id`        BIGINT UNSIGNED DEFAULT NULL            COMMENT '笔记本或笔记 ID；all 时为空',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_sequence` (`conversation_id`, `sequence_no`),
    UNIQUE KEY `uk_conversation_client_message` (`conversation_id`, `client_message_id`),
    KEY `idx_user_conversation` (`user_id`, `conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 助手会话消息表';

-- ------------------------------------------------------------
-- 11. AI 回答反馈表
-- ------------------------------------------------------------
CREATE TABLE `ai_answer_feedback` (
    `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`                BIGINT UNSIGNED NOT NULL                COMMENT '所属用户',
    `conversation_client_id` VARCHAR(64)     NOT NULL                COMMENT '前端会话标识',
    `message_client_id`      VARCHAR(64)     NOT NULL                COMMENT '被评价的助手消息标识',
    `rating`                 VARCHAR(16)     NOT NULL                COMMENT '评价：helpful / unhelpful',
    `reason`                 VARCHAR(32)     DEFAULT NULL            COMMENT '无用原因',
    `comment`                VARCHAR(500)    DEFAULT NULL            COMMENT '补充说明',
    `question`               VARCHAR(500)    NOT NULL                COMMENT '评价时的问题快照',
    `answer`                 MEDIUMTEXT      NOT NULL                COMMENT '评价时的回答快照',
    `sources_json`           MEDIUMTEXT      NULL                    COMMENT '评价时的引用片段 JSON',
    `scope_type`             VARCHAR(16)     NOT NULL DEFAULT 'all'  COMMENT '检索范围：all / notebook / note',
    `scope_id`               BIGINT UNSIGNED DEFAULT NULL            COMMENT '笔记本或笔记 ID',
    `eval_case_id`           BIGINT UNSIGNED DEFAULT NULL            COMMENT '由该反馈转换出的 RAG 回归测试用例 ID',
    `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次反馈时间',
    `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_conversation_message` (`user_id`, `conversation_client_id`, `message_client_id`),
    UNIQUE KEY `uk_user_eval_case` (`user_id`, `eval_case_id`),
    KEY `idx_user_rating_time` (`user_id`, `rating`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 回答反馈表';

-- ------------------------------------------------------------
-- 12. RAG 检索回归测试用例
-- ------------------------------------------------------------
CREATE TABLE `ai_rag_eval_case` (
    `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`                BIGINT UNSIGNED NOT NULL                COMMENT '所属用户',
    `name`                   VARCHAR(100)    NOT NULL                COMMENT '用例名称',
    `question`               VARCHAR(500)    NOT NULL                COMMENT '检索问题',
    `scope_type`             VARCHAR(16)     NOT NULL DEFAULT 'all'  COMMENT '范围：all / notebook / note',
    `scope_id`               BIGINT UNSIGNED DEFAULT NULL            COMMENT '笔记本或笔记 ID',
    `expect_answer`          TINYINT         NOT NULL DEFAULT 1      COMMENT '1 应命中 / 0 应拒答',
    `expected_note_ids_json` VARCHAR(500)    NOT NULL DEFAULT '[]'   COMMENT '允许命中的笔记 ID JSON',
    `match_mode`             VARCHAR(8)      NOT NULL DEFAULT 'any' COMMENT '预期笔记命中要求：any 任意一篇 / all 全部笔记',
    `min_score`              DECIMAL(5,4)    NOT NULL DEFAULT 0.4500 COMMENT '判定相关的最低相似度',
    `enabled`                TINYINT         NOT NULL DEFAULT 1      COMMENT '是否参与批量运行',
    `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_enabled` (`user_id`, `enabled`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 检索回归测试用例';

-- ------------------------------------------------------------
-- 13. RAG 检索回归运行报告
-- ------------------------------------------------------------
CREATE TABLE `ai_rag_eval_run` (
    `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`                 BIGINT UNSIGNED NOT NULL                COMMENT '所属用户',
    `case_count`              INT UNSIGNED    NOT NULL                COMMENT '运行用例数',
    `passed_count`            INT UNSIGNED    NOT NULL                COMMENT '通过数',
    `positive_case_count`     INT UNSIGNED    NOT NULL                COMMENT '应命中用例数',
    `hit_count`               INT UNSIGNED    NOT NULL                COMMENT '命中预期笔记的用例数',
    `negative_case_count`     INT UNSIGNED    NOT NULL                COMMENT '应拒答用例数',
    `correct_rejection_count` INT UNSIGNED    NOT NULL                COMMENT '正确拒答数',
    `wrong_reference_count`   INT UNSIGNED    NOT NULL                COMMENT '阈值以上的非预期笔记数量',
    `average_duration_ms`     INT UNSIGNED    NOT NULL                COMMENT '平均单用例检索耗时',
    `results_json`            MEDIUMTEXT      NOT NULL                COMMENT '逐用例结果 JSON',
    `create_time`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '运行时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 检索回归运行报告';
