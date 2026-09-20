-- AI 助手会话持久化：一个会话包含多条按顺序排列的用户/助手消息
USE cloud_notepad;

CREATE TABLE IF NOT EXISTS `ai_conversation` (
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

CREATE TABLE IF NOT EXISTS `ai_conversation_message` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属会话（逻辑外键 -> ai_conversation.id）',
    `user_id`         BIGINT UNSIGNED NOT NULL                COMMENT '所属用户，用于查询隔离',
    `sequence_no`     INT UNSIGNED    NOT NULL                COMMENT '消息在会话中的顺序，从 0 开始',
    `role`            VARCHAR(16)     NOT NULL                COMMENT '角色：user / assistant',
    `content`         MEDIUMTEXT      NOT NULL                COMMENT '消息正文',
    `question`        VARCHAR(500)    DEFAULT NULL            COMMENT '助手消息对应的原始问题',
    `sources_json`    MEDIUMTEXT      NULL                    COMMENT '回答引用片段 JSON',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_sequence` (`conversation_id`, `sequence_no`),
    KEY `idx_user_conversation` (`user_id`, `conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 助手会话消息表';
