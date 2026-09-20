package com.notepad.ai.dto;

import java.time.LocalDateTime;

public record AiAnswerFeedbackResponse(
        String rating,
        String reason,
        String comment,
        Long evalCaseId,
        LocalDateTime updatedAt
) {
}
