-- 将“没帮助”反馈与由其转换出的 RAG 回归测试用例关联，防止重复转换
USE cloud_notepad;

ALTER TABLE `ai_answer_feedback`
    ADD COLUMN `eval_case_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '由该反馈转换出的 RAG 回归测试用例 ID'
        AFTER `scope_id`,
    ADD UNIQUE KEY `uk_user_eval_case` (`user_id`, `eval_case_id`);
