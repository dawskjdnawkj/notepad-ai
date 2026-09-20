-- 搜索改用剥离 HTML 后的 content_text，避免扫描富文本并让查询命中全文索引
ALTER TABLE `note`
    DROP INDEX `ft_title_content`,
    ADD FULLTEXT INDEX `ft_title_content_text` (`title`, `content_text`) WITH PARSER ngram;
