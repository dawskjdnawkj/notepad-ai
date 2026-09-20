package com.notepad.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class NoteUpdateRequest {

    @Size(max = 200, message = "标题最长 200 字符")
    private String title;

    @Size(max = 1000000, message = "内容超出限制（最多 100 万字符）")
    private String content;

    private List<Long> tagIds;
}
