package com.notepad.ai.dto;

import java.time.LocalDateTime;

public record RagEvalRunSummaryResponse(
        Long id,
        int caseCount,
        int passedCount,
        double passRate,
        double hitRate,
        double rejectionAccuracy,
        int wrongReferenceCount,
        int averageDurationMs,
        LocalDateTime createdAt
) {
}
