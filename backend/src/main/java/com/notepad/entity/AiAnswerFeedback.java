package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_answer_feedback")
public class AiAnswerFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String conversationClientId;

    private String messageClientId;

    private String rating;

    private String reason;

    private String comment;

    private String question;

    private String answer;

    private String sourcesJson;

    private String scopeType;

    private Long scopeId;

    private Long evalCaseId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
