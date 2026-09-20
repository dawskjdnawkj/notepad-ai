package com.notepad.vo;

import com.notepad.vo.ReminderVO;
import com.notepad.vo.TagVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NoteDetailVO {

    private Long id;
    private String title;
    private String content;
    private Integer pinned;
    private Long notebookId;
    private String notebookName;
    private List<TagVO> tags;
    private ReminderVO reminder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
