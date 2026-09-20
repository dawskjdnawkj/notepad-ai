package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Embedding 教学接口参数：text1 应与 text2 语义相近，与 text3 语义无关。
 */
public record EmbeddingTestRequest(
        @NotBlank(message = "text1 不能为空") String text1,
        @NotBlank(message = "text2 不能为空") String text2,
        @NotBlank(message = "text3 不能为空") String text3
) {
}
