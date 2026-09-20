package com.notepad.ai.dto;

import java.util.List;

public record RetrievalQueryTrace(
        String query,
        List<RetrievalCandidateTrace> candidates
) {
}
