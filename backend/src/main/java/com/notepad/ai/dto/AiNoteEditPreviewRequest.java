package com.notepad.ai.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 生成 AI 编辑预览的请求。
 */
public record AiNoteEditPreviewRequest(
        @NotNull(message = "必须指定 AI 编辑能力")
        AiNoteEditCapability capability
) {
}
