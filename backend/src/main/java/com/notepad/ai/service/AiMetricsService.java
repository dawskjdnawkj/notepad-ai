package com.notepad.ai.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * AI 请求的运行期统计。纯内存计数，服务重启后归零。
 *
 * 不做持久化：交接文档要求的是「有日志证据」，而每个计数点同时都会打一条
 * {@code event=...} 结构化日志，历史趋势可以直接对日志聚合得到。
 * 需要真正的趋势报表时再单开一阶段落库。
 */
@Service
public class AiMetricsService {

    /** 耗时分布的上界（毫秒），最后一档为「大于最后一档」。 */
    private static final long[] DURATION_BOUNDS_MILLIS = {1_000L, 3_000L, 10_000L, 30_000L, 60_000L};
    private static final String OVERFLOW_LABEL = ">60s";

    private final LongAdder rejectedStream = new LongAdder();
    private final LongAdder rejectedBlocking = new LongAdder();
    private final LongAdder rejectedQueue = new LongAdder();
    private final LongAdder timeout = new LongAdder();
    private final LongAdder retry = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder[] durationBuckets = newDurationBuckets();

    /** 流式问答因并发上限被拒绝。 */
    public void recordStreamRejected() {
        rejectedStream.increment();
    }

    /** 阻塞式问答因并发上限被拒绝。 */
    public void recordBlockingRejected() {
        rejectedBlocking.increment();
    }

    /** 因 AI 线程池队列已满被拒绝。 */
    public void recordQueueRejected() {
        rejectedQueue.increment();
    }

    public void recordTimeout() {
        timeout.increment();
    }

    /** 由重试监听器调用，语义是「确实又发起了一次尝试」。 */
    public void recordRetry() {
        retry.increment();
    }

    /** 记录一次成功完成的问答，并把它计入耗时分布。 */
    public void recordCompleted(long durationMillis) {
        completed.increment();
        recordDuration(durationMillis);
    }

    /** 记录一次失败的问答，并把它计入耗时分布。 */
    public void recordFailed(long durationMillis) {
        failed.increment();
        recordDuration(durationMillis);
    }

    private void recordDuration(long durationMillis) {
        long value = Math.max(0L, durationMillis);
        for (int i = 0; i < DURATION_BOUNDS_MILLIS.length; i++) {
            if (value <= DURATION_BOUNDS_MILLIS[i]) {
                durationBuckets[i].increment();
                return;
            }
        }
        durationBuckets[DURATION_BOUNDS_MILLIS.length].increment();
    }

    public Snapshot snapshot() {
        Map<String, Long> distribution = new LinkedHashMap<>();
        long previousBound = 0L;
        for (int i = 0; i < DURATION_BOUNDS_MILLIS.length; i++) {
            distribution.put(label(previousBound, DURATION_BOUNDS_MILLIS[i]), durationBuckets[i].sum());
            previousBound = DURATION_BOUNDS_MILLIS[i];
        }
        distribution.put(OVERFLOW_LABEL, durationBuckets[DURATION_BOUNDS_MILLIS.length].sum());

        return new Snapshot(
                rejectedStream.sum(),
                rejectedBlocking.sum(),
                rejectedQueue.sum(),
                timeout.sum(),
                retry.sum(),
                completed.sum(),
                failed.sum(),
                distribution);
    }

    private String label(long lowerBoundExclusive, long upperBoundInclusive) {
        String upper = upperBoundInclusive % 1_000L == 0
                ? (upperBoundInclusive / 1_000L) + "s"
                : upperBoundInclusive + "ms";
        if (lowerBoundExclusive == 0L) {
            return "<=" + upper;
        }
        return (lowerBoundExclusive / 1_000L) + "s~" + upper;
    }

    private static LongAdder[] newDurationBuckets() {
        LongAdder[] buckets = new LongAdder[DURATION_BOUNDS_MILLIS.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        return buckets;
    }

    /**
     * 计数的不可变快照。
     */
    public record Snapshot(
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
}
