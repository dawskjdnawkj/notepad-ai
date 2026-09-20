package com.notepad.ai.dto;

public record RetrievalCandidateTrace(
        Long noteId,
        String title,
        Double score,
        boolean selected
) {
}
