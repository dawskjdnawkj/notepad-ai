package com.notepad.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RagEvalCaseResponse(
        Long id,
        String name,
        String question,
        String scopeType,
        Long scopeId,
        boolean expectAnswer,
        List<Long> expectedNoteIds,
        String matchMode,
        double minScore,
        boolean enabled,
        LocalDateTime updatedAt
) {
}
