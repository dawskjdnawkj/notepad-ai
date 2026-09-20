package com.notepad.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReminderRequest {

    @NotBlank(message = "unit 不能为空")
    private String unit;

    @NotNull(message = "value 不能为空")
    @Min(value = 1, message = "value 须为 1~43200 的整数")
    @Max(value = 43200, message = "value 须为 1~43200 的整数")
    private Integer value;
}
