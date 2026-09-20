package com.notepad.ai.dto;

import java.util.List;

public record NoteSearchResult(
        List<NoteSearchItem> items,
        List<RetrievalQueryTrace> traces
) {
}
