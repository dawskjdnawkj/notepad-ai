-- AI 编辑笔记的原文快照表，用于写回后回退原文

USE cloud_notepad;

CREATE TABLE `ai_note_revision` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT UNSIGNED NOT NULL                COMMENT '所属用户',
    `note_id`        BIGINT UNSIGNED NOT NULL                COMMENT '被 AI 编辑的笔记',
    `capability`     VARCHAR(32)     NOT NULL                COMMENT 'AI 能力：summarize / rewrite / continue / todos',
    `title_before`   VARCHAR(200)    DEFAULT NULL            COMMENT '写回前的标题',
    `content_before` MEDIUMTEXT      NOT NULL                COMMENT '写回前的正文 HTML，用于回退原文',
    `content_after`  MEDIUMTEXT      NULL                    COMMENT '写回后的正文 HTML，便于审计对比',
    `restored`       TINYINT         NOT NULL DEFAULT 0      COMMENT '是否已回退：0 否 / 1 是',
    `restore_time`   DATETIME        DEFAULT NULL            COMMENT '回退时间',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_note_time` (`user_id`, `note_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 编辑笔记的原文快照';
