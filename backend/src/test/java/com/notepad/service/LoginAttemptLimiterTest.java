package com.notepad.service;

import com.notepad.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限流。
 * <p>
 * 这个组件的语义全是边界，而且出过一次真实的并发缺陷（判定与计数分两步，
 * 并发突发能突破阈值），所以下面既测阈值边界，也测并发下的实际放行次数。
 */
class LoginAttemptLimiterTest {

    private static final String USER = "alice";
    private static final String IP = "1.2.3.4";

    /** 阈值 3、IP 上限放到很大，隔离出「用户名 + IP」这一维 */
    private LoginAttemptLimiter userDimension(Duration window) {
        return new LoginAttemptLimiter(3, 1000, window);
    }

    private void acquire(LoginAttemptLimiter limiter, String user, String ip) {
        limiter.tryAcquire(user, ip);
    }

    private void assertRejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("阈值内放行，第 max+1 次才拒绝")
    void allowsUpToThresholdThenRejects() {
        LoginAttemptLimiter limiter = userDimension(Duration.ofMinutes(15));

        for (int i = 0; i < 3; i++) {
            int attempt = i;
            assertThatCode(() -> acquire(limiter, USER, IP))
                    .as("第 %d 次尝试应放行", attempt + 1)
                    .doesNotThrowAnyException();
        }
        assertRejected(() -> acquire(limiter, USER, IP));
    }

    @Test
    @DisplayName("锁定期内继续尝试不延长锁定：锁定应到点就解，不能被反复重试续期")
    void lockoutIsNotExtendedByFurtherAttempts() throws InterruptedException {
        LoginAttemptLimiter limiter = userDimension(Duration.ofMillis(200));
        for (int i = 0; i < 4; i++) {
            assertRejectedOrIgnore(limiter);
        }
        assertRejected(() -> acquire(limiter, USER, IP));

        // 锁定过半时再猛试一阵；如果每次失败都续期，下面就不可能解开
        Thread.sleep(100);
        for (int i = 0; i < 10; i++) {
            assertRejected(() -> acquire(limiter, USER, IP));
        }

        Thread.sleep(150);
        assertThatCode(() -> acquire(limiter, USER, IP))
                .as("总共已过 250ms > 200ms 窗口，锁定应已自然解除")
                .doesNotThrowAnyException();
    }

    private void assertRejectedOrIgnore(LoginAttemptLimiter limiter) {
        try {
            acquire(limiter, USER, IP);
        } catch (BusinessException ignored) {
            // 前几次是放行的，只有超过阈值才开始拒绝
        }
    }

    @Test
    @DisplayName("窗口过后锁定自然解除，用户重新获得完整额度")
    void lockoutExpiresAfterWindow() throws InterruptedException {
        LoginAttemptLimiter limiter = userDimension(Duration.ofMillis(150));
        for (int i = 0; i < 4; i++) {
            assertRejectedOrIgnore(limiter);
        }
        assertRejected(() -> acquire(limiter, USER, IP));

        Thread.sleep(200);

        assertThatCode(() -> acquire(limiter, USER, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("登录成功清零「用户名 + IP」维度")
    void releaseClearsUserDimension() {
        LoginAttemptLimiter limiter = userDimension(Duration.ofMinutes(15));
        acquire(limiter, USER, IP);
        acquire(limiter, USER, IP);

        limiter.release(USER, IP);

        // 清零之后再失败 3 次仍应放行，说明计数确实回到了 0
        assertThatCode(() -> {
            acquire(limiter, USER, IP);
            acquire(limiter, USER, IP);
            acquire(limiter, USER, IP);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单 IP 维度跨用户名生效：换新用户名也会被拦住")
    void perIpDimensionSpansUsernames() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1000, 2, Duration.ofMinutes(15));

        acquire(limiter, "u1", IP);
        acquire(limiter, "u2", IP);

        assertRejected(() -> acquire(limiter, "u3", IP));
    }

    @Test
    @DisplayName("被用户名维度拦下的请求要退还 IP 额度：否则重试会连累整个出口 IP")
    void rejectedAttemptDoesNotConsumeIpBudget() {
        // IP 上限 3，用户名上限 1
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 3, Duration.ofMinutes(15));

        acquire(limiter, USER, IP);          // 占 1 个用户名额度 + 1 个 IP 额度
        // 同一个用户名再试 5 次：都会被用户名维度拒绝，且每次都归还 IP 额度
        for (int i = 0; i < 5; i++) {
            assertRejected(() -> acquire(limiter, USER, IP));
        }

        // IP 额度没被这些注定失败的请求吃掉，换个用户名仍然能登录
        assertThatCode(() -> acquire(limiter, "bob", IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("登录成功只归还 IP 额度、不清零：攻击者不能靠登自己账号抹掉累积的失败")
    void releaseRefundsOneIpSlotOnly() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1000, 3, Duration.ofMinutes(15));

        acquire(limiter, "victim", IP);
        acquire(limiter, "victim", IP);
        acquire(limiter, "victim", IP);      // IP 计数到 3，再有一次就锁

        limiter.release("attacker", IP);     // 攻击者用自己的账号成功登录

        // 只退还 1 个，仍有 2 个失败挂着，所以第 2 次尝试就会触发锁定
        assertThatCode(() -> acquire(limiter, "victim", IP)).doesNotThrowAnyException();
        assertRejected(() -> acquire(limiter, "victim", IP));
    }

    @Test
    @DisplayName("并发突发也不能突破阈值（回归：曾经判定与计数分两步，一次突发能拿到 15 次猜测）")
    void concurrentBurstCannotExceedThreshold() throws Exception {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 1000, Duration.ofMinutes(1));
        int threads = 15;

        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        try {
                            limiter.tryAcquire("burst", IP);
                            allowed.incrementAndGet();
                        } catch (BusinessException expected) {
                            // 被限流拦住
                        }
                        return null;
                    })
                    .toList();

            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("不同用户名 + IP 互不干扰")
    void differentKeysAreIndependent() {
        LoginAttemptLimiter limiter = userDimension(Duration.ofMinutes(15));
        for (int i = 0; i < 4; i++) {
            assertRejectedOrIgnore(limiter);
        }
        assertRejected(() -> acquire(limiter, USER, IP));

        assertThatCode(() -> acquire(limiter, USER, "5.6.7.8")).doesNotThrowAnyException();
        assertThatCode(() -> acquire(limiter, "someone-else", IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("构造参数非法时直接拒绝启动，而不是运行时才算错")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(0, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(5, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(5, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
