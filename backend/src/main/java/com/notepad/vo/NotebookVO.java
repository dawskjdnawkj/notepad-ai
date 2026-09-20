package com.notepad.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class NotebookVO {

    private Long id;
    private String name;
    private Boolean isDefault;
    private Long noteCount;
    private LocalDateTime createTime;
}
