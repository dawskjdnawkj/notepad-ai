package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(

        @NotBlank(message = "问题不能为空")
        String message

) {
}