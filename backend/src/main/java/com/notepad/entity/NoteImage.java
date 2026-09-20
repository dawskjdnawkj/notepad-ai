package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note_image")
public class NoteImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long noteId;

    private String url;

    private String fileName;

    private String originalName;

    private Long size;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
