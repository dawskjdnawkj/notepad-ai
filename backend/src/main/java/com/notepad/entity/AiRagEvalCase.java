package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_rag_eval_case")
public class AiRagEvalCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String question;

    private String scopeType;

    private Long scopeId;

    private Integer expectAnswer;

    private String expectedNoteIdsJson;

    private String matchMode;

    private BigDecimal minScore;

    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
