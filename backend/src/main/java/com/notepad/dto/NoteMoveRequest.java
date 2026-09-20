package com.notepad.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NoteMoveRequest {

    @NotNull(message = "notebookId 不能为空")
    private Long notebookId;
}
