package com.notepad.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AiConversationMessagePayload(
        @NotBlank(message = "消息角色不能为空")
        @Pattern(regexp = "user|assistant", message = "消息角色只能是 user 或 assistant")
        String role,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 20000, message = "消息内容最多 20000 个字符")
        String content,

        @Size(max = 500, message = "原始问题最多 500 个字符")
        String question,

        @Valid
        @Size(max = 5, message = "每条回答最多保存 5 个引用片段")
        List<AiConversationSource> sources,

        @Pattern(regexp = "[A-Za-z0-9-]{1,64}", message = "消息 ID 格式不正确")
        String clientMessageId,

        @Pattern(regexp = "all|notebook|note", message = "检索范围不正确")
        String scopeType,

        @Positive(message = "检索范围 ID 必须为正数")
        Long scopeId,

        AiAnswerFeedbackResponse feedback
) {
}
