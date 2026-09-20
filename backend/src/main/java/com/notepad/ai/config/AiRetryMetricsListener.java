package com.notepad.ai.config;

import com.notepad.ai.service.AiMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 给 Spring AI 自动配置的 RetryTemplate 附加一个只读的重试监听器。
 *
 * Spring AI 的 RetryTemplate 由 SpringAiRetryAutoConfiguration 以 @ConditionalOnMissingBean
 * 创建，它只注册了自己的日志监听器，**不会**收集容器里的 RetryListener bean。
 * 所以这里直接拿到那个 bean 调 registerListener 挂上去。
 *
 * 这是纯附加的观测：不替换 bean、不改重试策略、不改退避参数、不改最大次数，
 * 因此不会影响任何既有重试行为，也不会影响 RAG 的检索结果。
 */
@Component
public class AiRetryMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(AiRetryMetricsListener.class);
    /** 记在 RetryContext 上的失败尝试次数，用于在 close 时推算本次操作重试了几次。 */
    private static final String FAILED_ATTEMPTS_KEY =
            AiRetryMetricsListener.class.getName() + ".failedAttempts";

    private final AiMetricsService metrics;

    public AiRetryMetricsListener(RetryTemplate retryTemplate, AiMetricsService metrics) {
        this.metrics = metrics;
        retryTemplate.registerListener(new MetricsRetryListener());
        log.info("event=ai.retry.listener.attached");
    }

    private final class MetricsRetryListener implements RetryListener {

        @Override
        public <T, E extends Throwable> void onError(
                RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            Integer failedAttempts = (Integer) context.getAttribute(FAILED_ATTEMPTS_KEY);
            context.setAttribute(FAILED_ATTEMPTS_KEY, failedAttempts == null ? 1 : failedAttempts + 1);
        }

        @Override
        public <T, E extends Throwable> void close(
                RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            Integer failedAttempts = (Integer) context.getAttribute(FAILED_ATTEMPTS_KEY);
            if (failedAttempts == null) {
                // 一次就成功，没有发生过失败尝试，也就没有重试。
                return;
            }

            // 本操作的尝试次数 = 失败的次数 + 最后那次成功（若最终成功）。
            int attempts = failedAttempts + (throwable == null ? 1 : 0);
            int retries = attempts - 1;
            if (retries <= 0) {
                return;
            }
            for (int i = 0; i < retries; i++) {
                metrics.recordRetry();
            }
            log.warn("event=ai.retry.observed attempts={} retries={} eventuallySucceeded={}",
                    attempts, retries, throwable == null);
        }
    }
}
