-- 给 note 表增加置顶字段
ALTER TABLE `note`
    ADD COLUMN `pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '置顶：0 否 / 1 是' AFTER `content_text`;
