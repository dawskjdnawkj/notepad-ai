-- 笔记图片表（图片实体独立存储于服务器磁盘，正文 HTML 仅保存访问 URL）
-- 支持临时图片：笔记未创建完成时 note_id 为空，保存笔记后由后端绑定
CREATE TABLE `note_image` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '上传用户（逻辑外键 -> user.id）',
    `note_id`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '所属笔记（临时图片为空 -> note.id）',
    `url`           VARCHAR(500)    NOT NULL                COMMENT '访问 URL（相对路径，如 /uploads/images/...）',
    `file_name`     VARCHAR(255)    NOT NULL                COMMENT '磁盘相对路径（如 images/2026/08/uuid.png）',
    `original_name` VARCHAR(255)    DEFAULT NULL            COMMENT '原始文件名',
    `size`          BIGINT          NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_note` (`user_id`, `note_id`),
    KEY `idx_url` (`url`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '笔记图片表';
