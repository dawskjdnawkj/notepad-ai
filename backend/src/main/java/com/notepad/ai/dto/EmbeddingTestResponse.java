package com.notepad.ai.dto;

/**
 * Embedding 测试结果。余弦相似度越接近 1，代表两段文本的语义越相近。
 */
public record EmbeddingTestResponse(
        int dimensions,
        double text1ToText2,
        double text1ToText3
) {
}
