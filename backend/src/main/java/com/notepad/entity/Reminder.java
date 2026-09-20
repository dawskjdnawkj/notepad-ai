package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reminder")
public class Reminder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long noteId;

    private LocalDateTime remindAt;

    private Integer status;

    private LocalDateTime notifiedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
