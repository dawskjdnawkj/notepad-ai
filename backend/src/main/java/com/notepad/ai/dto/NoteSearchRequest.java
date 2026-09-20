package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record NoteSearchRequest(
        @NotBlank(message = "搜索问题不能为空")
        String question
) {
}
