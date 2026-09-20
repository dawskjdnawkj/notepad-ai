package com.notepad.ai.dto;

/**
 * 向量检索命中的一个笔记片段。
 */
public record NoteSearchItem(
        Long noteId,
        String title,
        String content,
        Double score
) {
}
