package com.notepad.ai.dto;

import java.time.LocalDateTime;

public record AiConversationSummaryResponse(
        String clientId,
        String title,
        long turnCount,
        LocalDateTime updatedAt
) {
}
