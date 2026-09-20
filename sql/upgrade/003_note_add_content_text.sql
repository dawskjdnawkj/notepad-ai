-- 给 note 表增加正文纯文本列（搜索用，HTML 剥离后）
ALTER TABLE `note`
    ADD COLUMN `content_text` MEDIUMTEXT NULL COMMENT '正文纯文本（搜索用，HTML 剥离后）' AFTER `content`;
