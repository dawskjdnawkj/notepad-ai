package com.notepad.ai.dto;

import java.time.Instant;

/**
 * 当前用户的笔记索引健康状态。
 *
 * storeType / indexType 让对比报告自描述：脚本不用靠外部变量记住自己跑的是哪一档。
 */
public record NoteIndexStatusResponse(
        int noteCount,
        int indexedNoteCount,
        int chunkCount,
        int missingNoteCount,
        int staleChunkCount,
        boolean storeFileExists,
        long storeFileBytes,
        Instant lastUpdatedAt,
        String state,
        String storeType,
        String indexType
) {
}
