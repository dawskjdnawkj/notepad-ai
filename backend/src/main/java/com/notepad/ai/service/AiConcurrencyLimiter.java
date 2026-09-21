package com.notepad.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicBoolean overLimit = new AtomicBoolean(false);
        AtomicReference<AtomicInteger> holder = new AtomicReference<>();
        // 判定与自增必须在同一个 compute 内完成。写在 compute 外面
        // （computeIfAbsent 拿到计数器再 incrementAndGet）会与 releaseCounter 的
        // 「减到 0 就 remove」交叉：remove 可能把另一个线程刚拿到并已自增到 1 的计数器摘掉，
        // 下一个请求就会新建计数器再次放行，同一个用户短暂持有 2 个许可；
        // 同时 activeCount() 少算，会让守恒律出现假阳性。
        userCounters.compute(counterKey, (key, existing) -> {
            AtomicInteger counter = existing != null ? existing : new AtomicInteger();
            if (counter.incrementAndGet() > perUserMax) {
                int left = counter.decrementAndGet();
                overLimit.set(true);
                return left == 0 ? null : counter;
            }
            holder.set(counter);
            return counter;
        });
        if (overLimit.get()) {
            globalSemaphore.release();
            return null;
        }
        return new Permit(counterKey, holder.get());
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

    /**
     * 同一个 compute 内完成「递减 + 减到 0 即删」，保证 map 里不会残留 count==0 的条目。
     * {@code existing == counter} 的身份检查同时挡住「该 key 已被新一轮请求重建」时误减别人的计数器。
     */
    private void releaseCounter(long counterKey, AtomicInteger counter) {
        userCounters.computeIfPresent(counterKey, (key, existing) ->
                existing == counter && existing.decrementAndGet() == 0 ? null : existing);
    }
}
