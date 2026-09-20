package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认写回 AI 编辑结果的请求。
 *
 * contentHash 是生成预览时笔记正文的 SHA-256，用于挡住「预览期间用户改了笔记」的竞态。
 */
public record AiNoteEditApplyRequest(
        @NotNull(message = "必须指定 AI 编辑能力")
        AiNoteEditCapability capability,

        @NotBlank(message = "AI 结果不能为空")
        String text,

        @NotBlank(message = "缺少预览时的内容指纹")
        String contentHash
) {
}
