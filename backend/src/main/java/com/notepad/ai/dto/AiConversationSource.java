package com.notepad.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiConversationSource(
        @NotNull(message = "引用笔记 ID 不能为空")
        @Positive(message = "引用笔记 ID 必须为正数")
        Long noteId,

        @NotBlank(message = "引用笔记标题不能为空")
        @Size(max = 500, message = "引用笔记标题最多 500 个字符")
        String title,

        @NotBlank(message = "引用内容不能为空")
        @Size(max = 10000, message = "引用内容最多 10000 个字符")
        String content,

        @NotNull(message = "引用相关度不能为空")
        @DecimalMin(value = "0.0", message = "引用相关度不能小于 0")
        @DecimalMax(value = "1.0", message = "引用相关度不能大于 1")
        Double score
) {
}
