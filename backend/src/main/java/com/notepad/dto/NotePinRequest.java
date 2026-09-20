package com.notepad.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NotePinRequest {

    @NotNull(message = "pinned 不能为空")
    private Boolean pinned;
}
