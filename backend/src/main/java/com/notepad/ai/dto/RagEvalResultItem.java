package com.notepad.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RagEvalResultItem(
        Long caseId,
        String name,
        String question,
        boolean expectAnswer,
        List<Long> expectedNoteIds,
        List<Long> matchedNoteIds,
        Double topScore,
        int wrongReferenceCount,
        long durationMs,
        boolean passed,
        String failureReason,
        List<RetrievalQueryTrace> retrievalTraces,
        SourceFeedback sourceFeedback
) {

    /**
     * 该用例由哪一条「没帮助」反馈转换而来；手工创建的用例为 null。
     *
     * 这条关联让回归报告可以直接追溯到用户当时的原始反馈，
     * 而不是只知道「有个用例失败了」。
     */
    public record SourceFeedback(
            Long feedbackId,
            String reason,
            String comment,
            LocalDateTime feedbackAt
    ) {
    }
}
