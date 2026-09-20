package com.notepad.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RagEvalRunResponse(
        Long id,
        int caseCount,
        int passedCount,
        double passRate,
        int positiveCaseCount,
        int hitCount,
        double hitRate,
        int negativeCaseCount,
        int correctRejectionCount,
        double rejectionAccuracy,
        int wrongReferenceCount,
        int averageDurationMs,
        LocalDateTime createdAt,
        List<RagEvalResultItem> results
) {
}
