package com.notepad.service;

import com.notepad.common.BusinessException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 邮箱验证码生成与校验（内存存储）。
 * <p>
 * 单机部署足够；验证码有效期 5 分钟，同一邮箱 60 秒内只允许发送一次。
 * 若后续需要多实例或重启不丢验证码，可替换为 Redis 存储（P1）。
 */
@Service
public class VerificationCodeService {

    /** 验证码有效期（毫秒） */
    private static final long CODE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 同一邮箱再次发送的最小间隔（毫秒） */
    private static final long RESEND_INTERVAL_MILLIS = 60 * 1000L;

    /** 校验失败最大次数，超过则作废验证码 */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 生成 6 位数字验证码并存储，返回验证码（供邮件发送）。
     */
    public String generate(String email) {
        long now = System.currentTimeMillis();
        Entry existing = store.get(email);
        if (existing != null && now - existing.lastSendAt < RESEND_INTERVAL_MILLIS) {
            throw new BusinessException(400, "发送过于频繁，请稍后再试");
        }
        String code = String.valueOf(RANDOM.nextInt(900000) + 100000);
        store.put(email, new Entry(code, now + CODE_TTL_MILLIS, now));
        return code;
    }

    /**
     * 校验验证码；校验通过后立即失效，防止重复使用。
     */
    public void verify(String email, String code) {
        Entry entry = store.get(email);
        if (entry == null || entry.expireAt < System.currentTimeMillis()) {
            store.remove(email);
            throw new BusinessException(400, "验证码错误或已过期");
        }
        if (!entry.code.equals(code)) {
            int attempts = entry.attempts.incrementAndGet();
            if (attempts >= MAX_ATTEMPTS) {
                store.remove(email);
                throw new BusinessException(400, "验证码错误次数过多，请重新获取");
            }
            throw new BusinessException(400, "验证码错误或已过期");
        }
        store.remove(email);
    }

    private static class Entry {
        final String code;
        final long expireAt;
        final long lastSendAt;
        final AtomicInteger attempts = new AtomicInteger(0);

        Entry(String code, long expireAt, long lastSendAt) {
            this.code = code;
            this.expireAt = expireAt;
            this.lastSendAt = lastSendAt;
        }
    }
}
