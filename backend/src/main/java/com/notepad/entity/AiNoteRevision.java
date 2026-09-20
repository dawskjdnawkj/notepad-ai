package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 编辑笔记时的原文快照。
 *
 * 刻意不加 @TableLogic：这是流水记录而不是可软删除的业务实体，
 * 靠「每篇笔记保留最近 N 份」轮转清理。
 */
@Data
@TableName("ai_note_revision")
public class AiNoteRevision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long noteId;

    private String capability;

    private String titleBefore;

    private String contentBefore;

    private String contentAfter;

    private Integer restored;

    private LocalDateTime restoreTime;

    private LocalDateTime createTime;
}
