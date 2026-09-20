package com.notepad.ai.dto;

import java.util.Map;

/**
 * AI 请求的运行期统计快照。
 *
 * 纯内存计数，服务重启后归零。每个计数点同时都会打一条 event=... 结构化日志，
 * 需要跨重启的历史趋势时对日志聚合即可。
 */
public record AiMetricsResponse(
        long rejectedStream,
        long rejectedBlocking,
        long rejectedQueue,
        long timeout,
        long retry,
        long completed,
        long failed,
        Map<String, Long> durationDistribution
) {
}
