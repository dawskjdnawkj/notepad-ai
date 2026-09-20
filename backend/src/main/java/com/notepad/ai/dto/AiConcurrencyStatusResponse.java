package com.notepad.ai.dto;

/**
 * AI 并发限制器的实时状态。
 *
 * 静止时刻满足 activeCount + availableGlobalPermits == globalMax，
 * 等式不成立即说明有请求路径没有释放并发许可。
 */
public record AiConcurrencyStatusResponse(
        int activeCount,
        int availableGlobalPermits,
        int globalMax,
        int perUserMax
) {
}
