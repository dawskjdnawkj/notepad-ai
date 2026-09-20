package com.notepad.ai.dto;

/**
 * 一篇笔记写入向量库后的结果。
 */
public record NoteIndexResponse(
        Long noteId,
        String title,
        int chunkCount
) {
}
