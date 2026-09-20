package com.notepad.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AiConversationSaveRequest(
        @NotBlank(message = "会话标题不能为空")
        @Size(max = 100, message = "会话标题最多 100 个字符")
        String title,

        @Valid
        @NotEmpty(message = "会话消息不能为空")
        @Size(max = 20, message = "每个会话最多保存 20 条消息")
        List<AiConversationMessagePayload> messages
) {
}
