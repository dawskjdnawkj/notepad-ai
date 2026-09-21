-- 让逻辑删除后的笔记本/标签名字可以复用。
--
-- 问题：uk_user_name(user_id, name) 里不含 deleted，软删的行仍然占着名字。
-- 应用层查重走 MyBatis-Plus，会自动补上 deleted = 0 所以查不到软删行，
-- 于是 insert 撞唯一键 → 409「数据已存在，请勿重复提交」，用户从此建不了这个名字。
--
-- 修法：把 deleted 从「0/1 标志」改成「0 正常 / 删除时间戳（epoch 秒）」，再把它收进唯一键。
-- 注意**不能**只把 deleted 加进唯一键而不改语义：那样两行 deleted = 1 的同名记录会互撞。
--
-- 回填安全性：旧唯一键 (user_id, name) 覆盖全部行，所以同一 (user_id, name) 至多一行，
-- 按主键回填不会互撞。nb_deleted_at 之类的专门列是另一种做法，这里不引入。
--
-- 已知边界：时间戳是秒级，同一秒内「删除 → 重建同名 → 再删除」理论上会撞键。
-- 该序列需要两次往返加两次点击，实际无法通过界面触发。

USE cloud_notepad;

ALTER TABLE `notebook`
    MODIFY COLUMN `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 删除时间戳（epoch 秒）';

ALTER TABLE `tag`
    MODIFY COLUMN `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 删除时间戳（epoch 秒）';

UPDATE `notebook` SET `deleted` = UNIX_TIMESTAMP(NOW()) WHERE `deleted` <> 0;
UPDATE `tag` SET `deleted` = UNIX_TIMESTAMP(NOW()) WHERE `deleted` <> 0;

ALTER TABLE `notebook`
    DROP INDEX `uk_user_name`,
    ADD UNIQUE KEY `uk_user_name` (`user_id`, `name`, `deleted`);

ALTER TABLE `tag`
    DROP INDEX `uk_user_name`,
    ADD UNIQUE KEY `uk_user_name` (`user_id`, `name`, `deleted`);
