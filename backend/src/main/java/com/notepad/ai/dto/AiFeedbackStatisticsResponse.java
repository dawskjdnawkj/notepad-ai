package com.notepad.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「没帮助」反馈的统计结果。
 *
 * evalStatus 把反馈与回归测试报告关联起来：由该问题转换出的用例在最近一次
 * 回归报告中是通过、失败，还是压根没跑过。
 * 取值 passed / failed / not_run，见每个高频问题条目。
 */
public record AiFeedbackStatisticsResponse(
        long totalCount,
        long helpfulCount,
        long unhelpfulCount,
        double helpfulRate,
        long convertedCaseCount,
        double conversionRate,
        List<ReasonCount> reasonCounts,
        List<CommonFailureQuestion> commonFailureQuestions) {

    public record ReasonCount(
            String reason,
            long count,
            double rate) {
    }

    public record CommonFailureQuestion(
            String question,
            long count,
            long convertedCaseCount,
            LocalDateTime lastFeedbackAt,
            List<ReasonCount> reasonCounts,
            String evalStatus,
            LocalDateTime lastEvalAt,
            String evalFailureReason) {
    }
}
