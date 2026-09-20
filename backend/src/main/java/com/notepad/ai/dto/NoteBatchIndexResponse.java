package com.notepad.ai.dto;

/**
 * 当前用户全部正常笔记写入向量库后的统计结果。
 */
public record NoteBatchIndexResponse(
        int noteCount,
        int chunkCount
) {
}
