package com.notepad.vo;

import com.notepad.vo.TagVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoteListItemVO {

    private Long id;
    private String title;
    private String contentPreview;
    private Integer pinned;
    private Long notebookId;
    private String notebookName;
    private List<TagVO> tags;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime deleteTime; // 仅回收站列表返回
}
