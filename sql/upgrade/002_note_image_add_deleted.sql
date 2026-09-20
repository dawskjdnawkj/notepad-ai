-- 给 note_image 表增加逻辑删除字段
ALTER TABLE `note_image`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除' AFTER `create_time`;
