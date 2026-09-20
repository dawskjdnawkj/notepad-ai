package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiAnswerFeedbackRequest(
        @NotBlank(message = "反馈类型不能为空")
        @Pattern(regexp = "helpful|unhelpful", message = "反馈类型不正确")
        String rating,

        @Pattern(
                regexp = "irrelevant_sources|incomplete|inconsistent|wrong_scope|other",
                message = "反馈原因不正确")
        String reason,

        @Size(max = 500, message = "补充说明最多 500 个字符")
        String comment
) {
}
