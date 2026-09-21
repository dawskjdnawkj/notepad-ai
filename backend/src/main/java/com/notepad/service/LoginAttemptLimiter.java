package com.notepad.service;

import com.notepad.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录失败限流（内存存储）。
 * <p>
 * 两个维度同时生效：一是「用户名 + IP」，防止针对单个账号的密码爆破；
 * 二是「单 IP」，防止攻击者换着用户名撞库。任一维度进入锁定期，登录一律返回 429。
 * 窗口与锁定时长相同（默认 15 分钟），窗口内成功登录会清零计数。
 * <p>
 * 单机部署足够；锁定期内重启进程即可清空所有计数（也正好是应急解锁手段）。
 * 若后续需要多实例，可替换为 Redis 计数（P1）。
 */
@Slf4j
@Service
public class LoginAttemptLimiter {

    /** 条目数超过该值时触发一次惰性清扫，防止攻击者用海量随机用户名撑爆内存 */
    private static final int PURGE_THRESHOLD = 10_000;

    /** 两次清扫之间的最小间隔，避免条目多时每次请求都做 O(n) 全表扫描 */
    private static final long PURGE_INTERVAL_MILLIS = 1000L;

    /** 登录接口对 username 没有长度约束，组合键里的用户名必须截断 */
    private static final int MAX_USERNAME_LENGTH = 64;

    /** 「用户名 + IP」维度 */
    private final Map<String, Attempt> userIpStore = new ConcurrentHashMap<>();

    /** 「单 IP」维度 */
    private final Map<String, Attempt> ipStore = new ConcurrentHashMap<>();

    /** 上次清扫时间，用于给清扫节流 */
    private final AtomicLong lastPurgeAt = new AtomicLong(0L);

    private final int maxPerUserIp;
    private final int maxPerIp;
    private final long windowMillis;

    public LoginAttemptLimiter(
            @Value("${notepad.auth.login-attempt.max-per-user-ip:5}") int maxPerUserIp,
            @Value("${notepad.auth.login-attempt.max-per-ip:20}") int maxPerIp,
            @Value("${notepad.auth.login-attempt.window:15m}") Duration window) {
        if (maxPerUserIp < 1 || maxPerIp < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("登录失败限流参数必须为正数");
        }
        this.maxPerUserIp = maxPerUserIp;
        this.maxPerIp = maxPerIp;
        this.windowMillis = window.toMillis();
    }

    /**
     * 登录前调用：**原子地占一个额度**并判断是否超限，超限抛 429。
     * <p>
     * 计数必须在验密之前完成。拆成「先 check、验密后再 record」两步的话，
     * 并发的多个请求会全部通过 check（那时计数还是 0），再去各自 BCrypt（50~100ms），
     * 最后才各自计数 —— 一次突发就能拿到与线程池等量的猜测次数，
     * 而不是配置的 5 次。这里把「判定 + 占位」收进 ConcurrentHashMap.compute 里一次做完。
     */
    public void tryAcquire(String username, String ip) {
        long now = System.currentTimeMillis();
        purgeIfOversized(userIpStore, now);
        purgeIfOversized(ipStore, now);
        // 先判「单 IP」维度：它每个 IP 只有一个条目，而「用户名 + IP」维度会为每个
        // 新用户名建一条。IP 已经锁定时就直接拒绝、不再建档，否则攻击者用同一 IP
        // 刷海量随机用户名就能让 userIpStore 无上限增长（容器只有 160m 堆）。
        long ipRemaining = acquire(ipStore, ipKey(ip), maxPerIp, "ip", now);
        if (ipRemaining > 0) {
            throw tooManyAttempts(ip, ipRemaining);
        }
        long userRemaining = acquire(userIpStore, userIpKey(username, ip), maxPerUserIp, "user-ip", now);
        if (userRemaining > 0) {
            // 这次请求被「用户名 + IP」维度拦下，根本没走到口令校验，不该占用 IP 维度的额度。
            // 不归还的话，一个用户被锁定后继续重试，会连带把这个出口 IP 也刷到锁定 ——
            // NAT 后面其他人跟着一起登不进来。
            refundIp(ip);
            throw tooManyAttempts(ip, userRemaining);
        }
    }

    private BusinessException tooManyAttempts(String ip, long remaining) {
        long minutes = Math.max(1L, (remaining + 59_999L) / 60_000L);
        log.warn("event=auth.login.rejected outcome=rate_limited ip={} remainingMs={}", ip, remaining);
        return new BusinessException(429, "登录失败次数过多，请 " + minutes + " 分钟后再试");
    }

    /**
     * 登录成功后调用。
     * <p>
     * 两个维度处理方式不同，这里是有意为之：
     * <ul>
     *   <li>「用户名 + IP」直接清零 —— 攻击者要对某个账号做爆破，得先知道该账号的密码
     *       才能触发清零，所以不构成绕过；而对误输几次的正常用户，清零是必要的体验。</li>
     *   <li>「单 IP」只归还本次占用的那一个额度，不清零 —— 理由见 {@link #refundIp}。</li>
     * </ul>
     */
    public void release(String username, String ip) {
        userIpStore.remove(userIpKey(username, ip));
        refundIp(ip);
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 归还 IP 维度占用的一个额度。只递减、不清零：清零会变成绕过手段 ——
     * 攻击者注册一个自己的账号，每撞库 19 次就用自己账号成功登录一次把计数抹掉，
     * IP 维度就完全失效了。递减只是抵消这一次尝试，不会抹掉之前的失败。
     */
    private void refundIp(String ip) {
        ipStore.computeIfPresent(ipKey(ip), (k, attempt) -> {
            if (attempt.count > 0) {
                attempt.count--;
            }
            return attempt;
        });
    }

    /**
     * 原子地累加一次尝试并返回需要等待的毫秒数（0 表示放行）。
     * 判定与自增在同一个 compute 内完成，因此并发请求会依次拿到各自的份额。
     */
    private long acquire(Map<String, Attempt> store, String key, int max, String dimension, long now) {
        AtomicLong rejectRemaining = new AtomicLong(0L);
        store.compute(key, (k, existing) -> {
            Attempt attempt = existing;
            if (attempt == null || attempt.isExpired(now, windowMillis)) {
                attempt = new Attempt(now);
            }
            if (attempt.lockedUntil > now) {
                // 已在锁定期：直接拒绝，既不重复计数也不延长锁定，保证锁定最多一个窗口
                rejectRemaining.set(attempt.lockedUntil - now);
                return attempt;
            }
            attempt.count++;
            if (attempt.count > max) {
                attempt.count = 0;
                attempt.windowStartAt = now;
                attempt.lockedUntil = now + windowMillis;
                rejectRemaining.set(windowMillis);
                log.warn("event=auth.login.locked outcome=threshold_reached dimension={} key={} max={}",
                        dimension, key, max);
            }
            return attempt;
        });
        return rejectRemaining.get();
    }

    /**
     * 内存上界保护：key 里含用户可控的 username，若攻击者用海量随机用户名各失败一次，
     * 惰性过期会让这些条目再也不被访问、永不回收。写到阈值时整体扫一遍过期项。
     * <p>
     * 清扫本身是 O(n)，若每个请求都扫，条目一多攻击者反而能用 CPU 把服务拖住，
     * 因此按固定间隔节流，最多每秒扫一次。
     */
    private void purgeIfOversized(Map<String, Attempt> store, long now) {
        if (store.size() < PURGE_THRESHOLD) {
            return;
        }
        long last = lastPurgeAt.get();
        if (now - last < PURGE_INTERVAL_MILLIS || !lastPurgeAt.compareAndSet(last, now)) {
            return;
        }
        store.entrySet().removeIf(entry -> entry.getValue().isExpired(now, windowMillis));
    }

    private static String userIpKey(String username, String ip) {
        String name = username == null ? "" : username;
        if (name.length() > MAX_USERNAME_LENGTH) {
            name = name.substring(0, MAX_USERNAME_LENGTH);
        }
        // 用 NUL 分隔，杜绝「用户名里带分隔符」造成的组合键碰撞
        return name + '\0' + ipKey(ip);
    }

    private static String ipKey(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    /**
     * 单个计数维度的窗口状态。字段用 volatile：写入都发生在 compute 内（同 key 串行），
     * 但 checkAllowed 是无锁读取，需要可见性保证。
     */
    private static final class Attempt {

        private volatile int count;
        private volatile long windowStartAt;
        /** 0 表示未锁定 */
        private volatile long lockedUntil;

        Attempt(long now) {
            this.windowStartAt = now;
        }

        boolean isExpired(long now, long windowMillis) {
            // 锁定期已过 且 统计窗口已过，整条记录作废。
            // 触发锁定时把 count 归零、windowStartAt 重置为锁定时刻，
            // 因此锁一到期，用户就重新获得完整的失败额度，不会残留粘滞状态。
            return lockedUntil <= now && now - windowStartAt > windowMillis;
        }
    }
}
