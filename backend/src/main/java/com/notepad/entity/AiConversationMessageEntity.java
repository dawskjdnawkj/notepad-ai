package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversation_message")
public class AiConversationMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    private String clientMessageId;

    private Integer sequenceNo;

    private String role;

    private String content;

    private String question;

    private String sourcesJson;

    private String scopeType;

    private Long scopeId;

    private LocalDateTime createTime;
}
