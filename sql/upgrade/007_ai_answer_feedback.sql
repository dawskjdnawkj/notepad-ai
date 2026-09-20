-- AI 回答反馈：为会话消息补充稳定客户端 ID 和检索范围，并保存有用/无用评价
USE cloud_notepad;

ALTER TABLE `ai_conversation_message`
    ADD COLUMN `client_message_id` VARCHAR(64) NULL COMMENT '前端生成的稳定消息标识' AFTER `user_id`,
    ADD COLUMN `scope_type` VARCHAR(16) NULL COMMENT '检索范围：all / notebook / note' AFTER `sources_json`,
    ADD COLUMN `scope_id` BIGINT UNSIGNED NULL COMMENT '笔记本或笔记 ID；all 时为空' AFTER `scope_type`;

UPDATE `ai_conversation_message`
SET `client_message_id` = CONCAT('legacy-', `id`)
WHERE `client_message_id` IS NULL OR `client_message_id` = '';

UPDATE `ai_conversation_message`
SET `scope_type` = 'all'
WHERE `role` = 'assistant' AND (`scope_type` IS NULL OR `scope_type` = '');

ALTER TABLE `ai_conversation_message`
    MODIFY COLUMN `client_message_id` VARCHAR(64) NOT NULL COMMENT '前端生成的稳定消息标识',
    ADD UNIQUE KEY `uk_conversation_client_message` (`conversation_id`, `client_message_id`);

CREATE TABLE IF NOT EXISTS `ai_answer_feedback` (
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
    `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次反馈时间',
    `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_conversation_message` (`user_id`, `conversation_client_id`, `message_client_id`),
    KEY `idx_user_rating_time` (`user_id`, `rating`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 回答反馈表';
