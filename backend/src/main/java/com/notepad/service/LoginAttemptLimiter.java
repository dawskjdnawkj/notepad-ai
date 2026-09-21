package com.notepad.service;

import com.notepad.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 登录接口对 username 没有长度约束，组合键里的用户名必须截断 */
    private static final int MAX_USERNAME_LENGTH = 64;

    /** 「用户名 + IP」维度 */
    private final Map<String, Attempt> userIpStore = new ConcurrentHashMap<>();

    /** 「单 IP」维度 */
    private final Map<String, Attempt> ipStore = new ConcurrentHashMap<>();

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
     * 登录前调用。任一维度处于锁定期则抛 429，消息里带上还需等待的分钟数。
     * <p>
     * 必须在校验密码之前调用，否则锁定期内猜中密码仍能登录。
     */
    public void checkAllowed(String username, String ip) {
        long now = System.currentTimeMillis();
        long remaining = Math.max(
                remainingLockMillis(userIpStore, userIpKey(username, ip), now),
                remainingLockMillis(ipStore, ipKey(ip), now));
        if (remaining > 0) {
            long minutes = Math.max(1L, (remaining + 59_999L) / 60_000L);
            log.warn("event=auth.login.rejected outcome=rate_limited ip={} remainingMs={}", ip, remaining);
            throw new BusinessException(429, "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
    }

    /**
     * 登录失败后调用。用户不存在与密码错误都算失败，否则「账号是否存在」会通过
     * 是否被锁定暴露出去（枚举信号）。
     */
    public void recordFailure(String username, String ip) {
        long now = System.currentTimeMillis();
        purgeIfOversized(userIpStore, now);
        purgeIfOversized(ipStore, now);
        record(userIpStore, userIpKey(username, ip), maxPerUserIp, "user-ip", now);
        record(ipStore, ipKey(ip), maxPerIp, "ip", now);
    }

    /**
     * 登录成功后清零，避免正常用户被自己之前的输入错误误伤。
     * <p>
     * IP 维度也一并清零：同一出口 IP（公司 NAT）下的正常登录不该被别人累积的失败拖累。
     * 主维度「用户名 + IP」不受其他账号登录影响，所以这不构成绕过。
     */
    public void reset(String username, String ip) {
        userIpStore.remove(userIpKey(username, ip));
        ipStore.remove(ipKey(ip));
    }

    // ------------------------------------------------------------------ 内部实现

    private void record(Map<String, Attempt> store, String key, int max, String dimension, long now) {
        // 复合的读-改-写必须整体放进 compute：ConcurrentHashMap 对同一个 key 持 bin 锁，
        // 而「先 computeIfAbsent 建 Entry、再在外面判断过期并自增」是两步，无法原子化。
        // 注意 lambda 里不能再操作同一个 map，否则会死锁。
        store.compute(key, (k, existing) -> {
            Attempt attempt = existing;
            if (attempt == null || attempt.isExpired(now, windowMillis)) {
                attempt = new Attempt(now);
            }
            if (attempt.lockedUntil > now) {
                // 锁定期内重复失败既不重复计数也不延长锁定，保证锁定最多一个窗口
                return attempt;
            }
            attempt.count++;
            if (attempt.count >= max) {
                attempt.count = 0;
                attempt.windowStartAt = now;
                attempt.lockedUntil = now + windowMillis;
                log.warn("event=auth.login.locked outcome=threshold_reached dimension={} key={} max={}",
                        dimension, key, max);
            }
            return attempt;
        });
    }

    private long remainingLockMillis(Map<String, Attempt> store, String key, long now) {
        Attempt attempt = store.get(key);
        if (attempt == null) {
            return 0L;
        }
        // 惰性过期：读时判断，过期即删。与 VerificationCodeService 一致，不引入后台清理线程
        if (attempt.isExpired(now, windowMillis)) {
            store.remove(key, attempt);
            return 0L;
        }
        return attempt.lockedUntil > now ? attempt.lockedUntil - now : 0L;
    }

    /**
     * 内存上界保护：key 里含用户可控的 username，若攻击者用海量随机用户名各失败一次，
     * 惰性过期会让这些条目再也不被访问、永不回收。写到阈值时整体扫一遍过期项。
     */
    private void purgeIfOversized(Map<String, Attempt> store, long now) {
        if (store.size() < PURGE_THRESHOLD) {
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
