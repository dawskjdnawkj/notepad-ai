package com.notepad.ai.controller;

import com.notepad.ai.dto.RagAnswerRequest;
import com.notepad.ai.service.RagAnswerService;
import com.notepad.ai.service.AiConcurrencyLimiter;
import com.notepad.ai.service.AiMetricsService;
import com.notepad.common.RequestTraceContext;
import com.notepad.common.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ai/notes")
public class AiStreamController {

    private static final Logger log = LoggerFactory.getLogger(AiStreamController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;
    private static final String OUTCOME_TIMEOUT = "timeout";
    private static final String OUTCOME_QUEUE_REJECTED = "queue_rejected";

    private final RagAnswerService ragAnswerService;
    private final Executor aiStreamExecutor;
    private final AiConcurrencyLimiter concurrencyLimiter;
    private final AiMetricsService metricsService;

    public AiStreamController(
            RagAnswerService ragAnswerService,
            @Qualifier("aiStreamExecutor") Executor aiStreamExecutor,
            AiConcurrencyLimiter concurrencyLimiter,
            AiMetricsService metricsService) {
        this.ragAnswerService = ragAnswerService;
        this.aiStreamExecutor = aiStreamExecutor;
        this.concurrencyLimiter = concurrencyLimiter;
        this.metricsService = metricsService;
    }

    /**
     * 事件顺序：status（检索中/生成中）→ delta（增量文本，可多次）
     * → sources（回答完成后的引用来源）→ done（结束）。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askNotesStream(
            @Valid @RequestBody RagAnswerRequest request,
            HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        String requestId = RequestTraceContext.currentId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        StreamTrace trace = new StreamTrace(
                requestId,
                userId,
                request.noteId(),
                request.notebookId(),
                System.nanoTime());

        // 明确禁止反向代理转换或缓冲 SSE 数据。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        AiConcurrencyLimiter.Permit permit = concurrencyLimiter.tryAcquire(userId);
        trace.permit = permit;
        if (permit == null) {
            response.setHeader("Retry-After", "2");
            metricsService.recordStreamRejected();
            log.warn(
                    "event=ai.stream.rejected userId={} scope={} outcome=concurrency_limited activeCount={}",
                    userId,
                    trace.scope(),
                    concurrencyLimiter.activeCount());
            sendEvent(emitter, "error", Map.of(
                    "message", "AI 当前请求较多，请稍后重试",
                    "errorId", requestId,
                    "retryAfterSeconds", 2));
            emitter.complete();
            return emitter;
        }

        emitter.onCompletion(() -> withRequestId(requestId, () -> {
            closed.set(true);
            dispose(subscriptionRef);
            trace.releasePermit();
            if (trace.terminalLogged.compareAndSet(false, true)) {
                metricsService.recordFailed(elapsedMillis(trace.startedAt));
                log.info(
                        "event=ai.stream.closed userId={} scope={} outcome=client_closed totalMs={}",
                        userId,
                        trace.scope(),
                        elapsedMillis(trace.startedAt));
            }
        }));
        emitter.onTimeout(() -> withRequestId(requestId, () -> {
            closed.set(true);
            dispose(subscriptionRef);
            trace.releasePermit();
            if (trace.terminalLogged.compareAndSet(false, true)) {
                metricsService.recordTimeout();
                metricsService.recordFailed(elapsedMillis(trace.startedAt));
                log.warn(
                        "event=ai.stream.failed userId={} scope={} outcome=timeout totalMs={}",
                        userId,
                        trace.scope(),
                        elapsedMillis(trace.startedAt));
            }
            sendEvent(emitter, "error", Map.of(
                    "message", "AI 回答超时，请稍后重试",
                    "errorId", requestId));
            emitter.complete();
        }));
        emitter.onError(error -> withRequestId(requestId, () -> {
            closed.set(true);
            dispose(subscriptionRef);
            trace.releasePermit();
            if (trace.terminalLogged.compareAndSet(false, true)) {
                metricsService.recordFailed(elapsedMillis(trace.startedAt));
                log.info(
                        "event=ai.stream.closed userId={} scope={} outcome=connection_error totalMs={}",
                        userId,
                        trace.scope(),
                        elapsedMillis(trace.startedAt));
            }
        }));

        if (!sendEvent(emitter, "status", Map.of(
                "phase", "retrieving",
                "requestId", requestId))) {
            closed.set(true);
            trace.releasePermit();
            emitter.complete();
            return emitter;
        }

        // 先把 SseEmitter 交还浏览器，再在专用线程中执行同步向量检索。
        try {
            aiStreamExecutor.execute(() -> startStream(
                    emitter,
                    userId,
                    request.question(),
                    request.history(),
                    request.noteId(),
                    request.notebookId(),
                    subscriptionRef,
                    closed,
                    trace));
        }
        catch (RuntimeException error) {
            if (hasCause(error, RejectedExecutionException.class)) {
                response.setHeader("Retry-After", "2");
            }
            completeWithError(emitter, error, trace, null);
        }
        return emitter;
    }

    private void startStream(
            SseEmitter emitter,
            Long userId,
            String question,
            java.util.List<RagAnswerRequest.ConversationMessage> history,
            Long noteId,
            Long notebookId,
            AtomicReference<Disposable> subscriptionRef,
            AtomicBoolean closed,
            StreamTrace trace) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                RequestTraceContext.MDC_KEY, trace.requestId)) {
            RagAnswerService.StreamAnswer streamAnswer =
                    ragAnswerService.streamAnswer(userId, question, history, noteId, notebookId);
            if (closed.get()) {
                return;
            }
            if (!sendEvent(emitter, "status", Map.of(
                    "phase", "generating",
                    "requestId", trace.requestId))) {
                closed.set(true);
                trace.releasePermit();
                emitter.complete();
                return;
            }

            trace.generationStartedAt.set(System.nanoTime());
            Disposable subscription = streamAnswer.content().subscribe(
                text -> withRequestId(trace.requestId, () -> {
                    if (text != null && !text.isEmpty()) {
                        trace.firstTokenMs.compareAndSet(-1, elapsedMillis(trace.startedAt));
                    }
                    if (text != null && !text.isEmpty()
                            && !sendEvent(emitter, "delta", Map.of("text", text))) {
                        closed.set(true);
                        dispose(subscriptionRef);
                        trace.releasePermit();
                        emitter.complete();
                    }
                }),
                error -> withRequestId(trace.requestId,
                        () -> completeWithError(
                                emitter,
                                error,
                                trace,
                                streamAnswer.retrievalDurationMs())),
                () -> withRequestId(trace.requestId, () -> {
                    if (sendEvent(emitter, "sources", streamAnswer.sources())) {
                        sendEvent(emitter, "done", Map.of("requestId", trace.requestId));
                    }
                    if (trace.terminalLogged.compareAndSet(false, true)) {
                        metricsService.recordCompleted(elapsedMillis(trace.startedAt));
                        log.info(
                                "event=ai.stream.completed userId={} scope={} outcome={} sourceCount={} retrievalMs={} firstTokenMs={} generationMs={} totalMs={}",
                                userId,
                                trace.scope(),
                                streamAnswer.sources().isEmpty() ? "no_answer" : "answered",
                                streamAnswer.sources().size(),
                                streamAnswer.retrievalDurationMs(),
                                trace.firstTokenMs.get(),
                                elapsedMillis(trace.generationStartedAt.get()),
                                elapsedMillis(trace.startedAt));
                    }
                    trace.releasePermit();
                    emitter.complete();
                }));
            subscriptionRef.set(subscription);
            if (closed.get()) {
                dispose(subscriptionRef);
            }
        }
        catch (RuntimeException error) {
            // 这个 catch 在 try-with-resources 之外执行，此时 MDC 已经被清空。
            // 不重新挂上 requestId 的话，这条终端失败日志会丢掉错误编号，
            // 客户端拿到的「错误编号」就和服务端日志对不上了。
            withRequestId(trace.requestId,
                    () -> completeWithError(emitter, error, trace, null));
        }
    }

    private void completeWithError(
            SseEmitter emitter,
            Throwable error,
            StreamTrace trace,
            Long retrievalDurationMs) {
        String outcome = classifyOutcome(error);
        long totalMs = elapsedMillis(trace.startedAt);
        // 计数放在与日志同一个 CAS 里，保证一次请求只被统计一次：
        // emitter.complete() 之后 onCompletion 还会再触发一次，不共用这个开关就会重复计数。
        if (trace.terminalLogged.compareAndSet(false, true)) {
            if (OUTCOME_TIMEOUT.equals(outcome)) {
                metricsService.recordTimeout();
            }
            else if (OUTCOME_QUEUE_REJECTED.equals(outcome)) {
                metricsService.recordQueueRejected();
            }
            metricsService.recordFailed(totalMs);
            log.error(
                    "event=ai.stream.failed userId={} scope={} outcome={} retrievalMs={} firstTokenMs={} totalMs={}",
                    trace.userId,
                    trace.scope(),
                    outcome,
                    retrievalDurationMs == null ? -1 : retrievalDurationMs,
                    trace.firstTokenMs.get(),
                    totalMs,
                    error);
        }
        sendEvent(emitter, "error", Map.of(
                "message", resolveErrorMessage(error),
                "errorId", trace.requestId));
        trace.releasePermit();
        emitter.complete();
    }

    private boolean sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
            return true;
        }
        catch (IOException | IllegalStateException exception) {
            log.debug("SSE 连接已关闭: event={}", eventName, exception);
            return false;
        }
    }

    private void dispose(AtomicReference<Disposable> subscriptionRef) {
        Disposable subscription = subscriptionRef.getAndSet(null);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private void withRequestId(String requestId, Runnable action) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(
                RequestTraceContext.MDC_KEY, requestId)) {
            action.run();
        }
    }

    private long elapsedMillis(long startedAt) {
        if (startedAt <= 0) {
            return -1;
        }
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static final class StreamTrace {

        private final String requestId;
        private final Long userId;
        private final Long noteId;
        private final Long notebookId;
        private final long startedAt;
        private final AtomicLong generationStartedAt = new AtomicLong(-1);
        private final AtomicLong firstTokenMs = new AtomicLong(-1);
        private final AtomicBoolean terminalLogged = new AtomicBoolean(false);
        private volatile AiConcurrencyLimiter.Permit permit;

        private StreamTrace(
                String requestId,
                Long userId,
                Long noteId,
                Long notebookId,
                long startedAt) {
            this.requestId = requestId;
            this.userId = userId;
            this.noteId = noteId;
            this.notebookId = notebookId;
            this.startedAt = startedAt;
        }

        private String scope() {
            if (noteId != null) {
                return "note:" + noteId;
            }
            if (notebookId != null) {
                return "notebook:" + notebookId;
            }
            return "all";
        }

        private void releasePermit() {
            AiConcurrencyLimiter.Permit current = permit;
            if (current != null) {
                current.close();
            }
        }
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 把失败原因归成可聚合的短标签，供按 outcome 统计和告警使用。
     * 与 resolveErrorMessage 的判断条件保持一致，但那个返回的是给用户看的中文提示。
     */
    private String classifyOutcome(Throwable error) {
        if (hasCause(error, SocketTimeoutException.class)
                || hasCause(error, HttpTimeoutException.class)
                || hasCause(error, TimeoutException.class)) {
            return OUTCOME_TIMEOUT;
        }
        if (hasCause(error, RejectedExecutionException.class)) {
            return OUTCOME_QUEUE_REJECTED;
        }

        WebClientResponseException responseException =
                findCause(error, WebClientResponseException.class);
        if (responseException != null) {
            return "upstream_" + responseException.getStatusCode().value();
        }
        return "failed";
    }

    private String resolveErrorMessage(Throwable error) {
        if (hasCause(error, SocketTimeoutException.class)
                || hasCause(error, HttpTimeoutException.class)
                || hasCause(error, TimeoutException.class)) {
            return "AI 回答超时，请稍后重试";
        }
        if (hasCause(error, RejectedExecutionException.class)) {
            return "AI 当前请求较多，请稍后重试";
        }

        WebClientResponseException responseException =
                findCause(error, WebClientResponseException.class);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            if (status == 429) {
                return "AI 服务当前请求较多，请稍后重试";
            }
            if (status == 401 || status == 403) {
                return "AI 服务鉴权失败，请检查百炼配置";
            }
            if (status >= 400 && status < 500) {
                return "AI 请求参数不被服务接受，请查看后端日志";
            }
            return "AI 服务暂时不可用，请稍后重试";
        }

        if (hasCause(error, ConnectException.class)
                || hasCause(error, IOException.class)) {
            return "连接 AI 服务失败，请检查网络后重试";
        }
        return "AI 回答生成失败，请查看后端日志中的错误编号";
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
