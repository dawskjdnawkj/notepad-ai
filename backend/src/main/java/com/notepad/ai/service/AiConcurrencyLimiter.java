package com.notepad.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限制对话模型的并发调用，避免突发请求把本地线程池或百炼配额耗尽。
 * 全局限制和单用户限制同时生效；Permit 必须在请求结束时关闭。
 */
@Service
public class AiConcurrencyLimiter {

    private final Semaphore globalSemaphore;
    private final int globalMax;
    private final int perUserMax;
    private final ConcurrentHashMap<Long, AtomicInteger> userCounters = new ConcurrentHashMap<>();

    public AiConcurrencyLimiter(
            @Value("${notepad.ai.concurrency.global-max:4}") int globalMax,
            @Value("${notepad.ai.concurrency.per-user-max:1}") int perUserMax) {
        if (globalMax < 1 || perUserMax < 1) {
            throw new IllegalArgumentException("AI 并发限制必须大于 0");
        }
        this.globalSemaphore = new Semaphore(globalMax);
        this.globalMax = globalMax;
        this.perUserMax = perUserMax;
    }

    public Permit tryAcquire(Long userId) {
        if (!globalSemaphore.tryAcquire()) {
            return null;
        }

        long counterKey = userId == null ? -1L : userId;
        AtomicInteger counter = userCounters.computeIfAbsent(counterKey, ignored -> new AtomicInteger());
        int current = counter.incrementAndGet();
        if (current > perUserMax) {
            releaseCounter(counterKey, counter);
            globalSemaphore.release();
            return null;
        }
        return new Permit(counterKey, counter);
    }

    public int activeCount() {
        return userCounters.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /**
     * 当前可用的全局许可数。
     *
     * 静止时刻满足守恒律：{@code activeCount() + availableGlobalPermits() == globalMax()}。
     * 因为 tryAcquire 只在拿到全局许可后才递增用户计数，超限回滚与 Permit.close
     * 也都是同时递减计数并释放全局许可，两者始终同增同减。
     * 任何一条请求路径漏掉 Permit.close()，这条等式立刻不成立，
     * 而且能区分是全局信号量泄漏还是单用户计数泄漏。
     */
    public int availableGlobalPermits() {
        return globalSemaphore.availablePermits();
    }

    public int globalMax() {
        return globalMax;
    }

    public int perUserMax() {
        return perUserMax;
    }

    public final class Permit implements AutoCloseable {
        private final long counterKey;
        private final AtomicInteger counter;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(long counterKey, AtomicInteger counter) {
            this.counterKey = counterKey;
            this.counter = counter;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseCounter(counterKey, counter);
                globalSemaphore.release();
            }
        }
    }

    private void releaseCounter(long counterKey, AtomicInteger counter) {
        if (counter.decrementAndGet() == 0) {
            userCounters.remove(counterKey, counter);
        }
    }
}
