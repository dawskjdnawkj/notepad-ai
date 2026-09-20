-- RAG 回归测试：区分“命中任意预期笔记”和“命中全部预期笔记”
USE cloud_notepad;

ALTER TABLE `ai_rag_eval_case`
    ADD COLUMN `match_mode` VARCHAR(8) NOT NULL DEFAULT 'any'
        COMMENT '预期笔记命中要求：any 任意一篇 / all 全部笔记'
        AFTER `expected_note_ids_json`;
