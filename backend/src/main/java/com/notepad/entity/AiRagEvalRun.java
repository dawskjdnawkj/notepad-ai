package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_rag_eval_run")
public class AiRagEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer caseCount;

    private Integer passedCount;

    private Integer positiveCaseCount;

    private Integer hitCount;

    private Integer negativeCaseCount;

    private Integer correctRejectionCount;

    private Integer wrongReferenceCount;

    private Integer averageDurationMs;

    private String resultsJson;

    private LocalDateTime createTime;
}
