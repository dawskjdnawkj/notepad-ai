package com.notepad.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NotebookRenameRequest {

    @NotBlank(message = "笔记本名称不能为空")
    @Size(max = 50, message = "笔记本名称最长 50 字符")
    private String name;
}
