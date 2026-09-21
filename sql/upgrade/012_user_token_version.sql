-- 用户 token 版本号：登出 / 修改密码 / 重置密码时 +1，使已签发的旧 JWT 立即失效。
-- 默认 0；升级前签发的存量 token 不带该 claim，解析时按 0 处理，因此本次升级不会强制已有用户重新登录。

USE cloud_notepad;

ALTER TABLE `user`
    ADD COLUMN `token_version` INT NOT NULL DEFAULT 0 COMMENT 'JWT 版本号：登出/改密码时 +1，使旧 token 立即失效' AFTER `status`;
