package com.notepad.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RagEvalCaseRequest(
        @NotBlank(message = "用例名称不能为空")
        @Size(max = 100, message = "用例名称最多 100 个字符")
        String name,

        @NotBlank(message = "测试问题不能为空")
        @Size(max = 500, message = "测试问题最多 500 个字符")
        String question,

        @NotBlank(message = "检索范围不能为空")
        @Pattern(regexp = "all|notebook|note", message = "检索范围不正确")
        String scopeType,

        @Positive(message = "检索范围 ID 必须为正数")
        Long scopeId,

        @NotNull(message = "必须指定是否应当命中")
        Boolean expectAnswer,

        @Size(max = 10, message = "每个用例最多配置 10 篇预期笔记")
        List<@Positive(message = "预期笔记 ID 必须为正数") Long> expectedNoteIds,

        @Pattern(regexp = "any|all", message = "预期笔记命中要求不正确")
        String matchMode,

        @NotNull(message = "最低相关度不能为空")
        @DecimalMin(value = "0.30", message = "最低相关度不能低于 0.30")
        @DecimalMax(value = "1.00", message = "最低相关度不能高于 1.00")
        Double minScore,

        @NotNull(message = "必须指定用例是否启用")
        Boolean enabled
) {
}
