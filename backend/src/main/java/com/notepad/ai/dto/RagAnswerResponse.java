package com.notepad.ai.dto;

import java.util.List;

/**
 * RAG 回答及本次回答实际使用的检索片段。
 */
public record RagAnswerResponse(
        String answer,
        List<NoteSearchItem> sources
) {
}
