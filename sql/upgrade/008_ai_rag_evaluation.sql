-- RAG 检索回归测试：保存用户定义的测试用例和每次批量运行报告
USE cloud_notepad;

CREATE TABLE IF NOT EXISTS `ai_rag_eval_case` (
    `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`                BIGINT UNSIGNED NOT NULL                COMMENT '所属用户',
    `name`                   VARCHAR(100)    NOT NULL                COMMENT '用例名称',
    `question`               VARCHAR(500)    NOT NULL                COMMENT '检索问题',
    `scope_type`             VARCHAR(16)     NOT NULL DEFAULT 'all'  COMMENT '范围：all / notebook / note',
    `scope_id`               BIGINT UNSIGNED DEFAULT NULL            COMMENT '笔记本或笔记 ID',
    `expect_answer`          TINYINT         NOT NULL DEFAULT 1      COMMENT '1 应命中 / 0 应拒答',
    `expected_note_ids_json` VARCHAR(500)    NOT NULL DEFAULT '[]'   COMMENT '允许命中的笔记 ID JSON',
    `min_score`              DECIMAL(5,4)    NOT NULL DEFAULT 0.4500 COMMENT '判定相关的最低相似度',
    `enabled`                TINYINT         NOT NULL DEFAULT 1      COMMENT '是否参与批量运行',
    `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_enabled` (`user_id`, `enabled`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 检索回归测试用例';

CREATE TABLE IF NOT EXISTS `ai_rag_eval_run` (
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
