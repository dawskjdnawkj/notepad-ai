package com.notepad.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TagVO {

    private Long id;
    private String name;
    private Long noteCount;
    private LocalDateTime createTime;

    public static TagVO brief(Long id, String name) {
        return new TagVO(id, name, null, null);
    }
}
