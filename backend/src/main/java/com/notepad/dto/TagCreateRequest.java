package com.notepad.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagCreateRequest {

    @NotBlank(message = "标签名称不能为空")
    @Size(max = 30, message = "标签名称最长 30 字符")
    private String name;
}
