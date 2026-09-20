package com.notepad.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AiConversationDetailResponse(
        String clientId,
        String title,
        LocalDateTime updatedAt,
        List<AiConversationMessagePayload> messages
) {
}
