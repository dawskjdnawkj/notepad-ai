package com.notepad.ai.controller;

import com.notepad.ai.dto.AiNoteEditApplyRequest;
import com.notepad.ai.dto.AiNoteEditApplyResponse;
import com.notepad.ai.dto.AiNoteEditPreviewRequest;
import com.notepad.ai.dto.AiNoteRevisionResponse;
import com.notepad.ai.service.AiConcurrencyLimiter;
import com.notepad.ai.service.AiMetricsService;
import com.notepad.ai.service.AiNoteEditService;
import com.notepad.common.RequestTraceContext;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.vo.NoteDetailVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 编辑笔记：预览走 SSE 流式，写回必须显式确认。
 *
 * 事件顺序：status（generating）→ delta（增量文本，可多次）→ done（带内容指纹）。
 * 错误事件为 error {message, errorId}，errorId 即请求编号。
 *
 * 写回不做流式，也不需要并发许可 —— 它不调用模型，只做校验、存快照、写库。
 */
@RestController
@RequestMapping("/api/ai/notes")
public class AiNoteEditController {

    private static final Logger log = LoggerFactory.getLogger(AiNoteEditController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private final AiNoteEditService aiNoteEditService;
    private final AiConcurrencyLimiter concurrencyLimiter;
    private final AiMetricsService metricsService;
    private final Executor aiStreamExecutor;

    public AiNoteEditController(
            AiNoteEditService aiNoteEditService,
            AiConcurrencyLimiter concurrencyLimiter,
            AiMetricsService metricsService,
            @Qualifier("aiStreamExecutor") Executor aiStreamExecutor) {
        this.aiNoteEditService = aiNoteEditService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.metricsService = metricsService;
        this.aiStreamExecutor = aiStreamExecutor;
    }

    @PostMapping(value = "/{noteId}/ai-edit/preview/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter previewStream(
            @PathVariable Long noteId,
            @Valid @RequestBody AiNoteEditPreviewRequest request,
            HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        String requestId = RequestTraceContext.currentId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        EditTrace trace = new EditTrace(requestId, userId, noteId);

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        AiConcurrencyLimiter.Permit permit = concurrencyLimiter.tryAcquire(userId);
        trace.permit = permit;
        if (permit == null) {
            response.setHeader("Retry-After", "2");
            metricsService.recordStreamRejected();
            log.warn("event=ai.note.edit.rejected userId={} noteId={} outcome=concurrency_limited activeCount={}",
                    userId, noteId, concurrencyLimiter.activeCount());
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
                log.info("event=ai.note.edit.closed userId={} noteId={} outcome=client_closed totalMs={}",
                        userId, noteId, elapsedMillis(trace.startedAt));
            }
        }));
        emitter.onTimeout(() -> withRequestId(requestId, () -> {
            closed.set(true);
            dispose(subscriptionRef);
            trace.releasePermit();
            if (trace.terminalLogged.compareAndSet(false, true)) {
                metricsService.recordTimeout();
                metricsService.recordFailed(elapsedMillis(trace.startedAt));
                log.warn("event=ai.note.edit.failed userId={} noteId={} outcome=timeout totalMs={}",
                        userId, noteId, elapsedMillis(trace.startedAt));
            }
            sendEvent(emitter, "error", Map.of(
                    "message", "AI 生成超时，请稍后重试",
                    "errorId", requestId));
            emitter.complete();
        }));
        emitter.onError(error -> withRequestId(requestId, () -> {
            closed.set(true);
            dispose(subscriptionRef);
            trace.releasePermit();
            if (trace.terminalLogged.compareAndSet(false, true)) {
                metricsService.recordFailed(elapsedMillis(trace.startedAt));
                log.info("event=ai.note.edit.closed userId={} noteId={} outcome=connection_error totalMs={}",
                        userId, noteId, elapsedMillis(trace.startedAt));
            }
        }));

        if (!sendEvent(emitter, "status", Map.of(
                "phase", "generating",
                "requestId", requestId))) {
            closed.set(true);
            trace.releasePermit();
            emitter.complete();
            return emitter;
        }

        try {
            aiStreamExecutor.execute(() -> startStream(
                    emitter, userId, noteId, request, subscriptionRef, closed, trace));
        }
        catch (RuntimeException error) {
            if (hasCause(error, RejectedExecutionException.class)) {
                response.setHeader("Retry-After", "2");
                metricsService.recordQueueRejected();
            }
            completeWithError(emitter, error, trace);
        }
        return emitter;
    }

    @PostMapping("/{noteId}/ai-edit/apply")
    public Result<AiNoteEditApplyResponse> apply(
            @PathVariable Long noteId,
            @Valid @RequestBody AiNoteEditApplyRequest request) {
        return Result.ok(aiNoteEditService.apply(UserContext.getUserId(), noteId, request));
    }

    @GetMapping("/{noteId}/ai-edit/revisions")
    public Result<List<AiNoteRevisionResponse>> revisions(@PathVariable Long noteId) {
        return Result.ok(aiNoteEditService.listRevisions(UserContext.getUserId(), noteId));
    }

    @PostMapping("/{noteId}/ai-edit/revisions/{revisionId}/restore")
    public Result<NoteDetailVO> restore(@PathVariable Long noteId, @PathVariable Long revisionId) {
        return Result.ok(aiNoteEditService.restore(UserContext.getUserId(), noteId, revisionId));
    }

    private void startStream(
            SseEmitter emitter,
            Long userId,
            Long noteId,
            AiNoteEditPreviewRequest request,
            AtomicReference<Disposable> subscriptionRef,
            AtomicBoolean closed,
            EditTrace trace) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestTraceContext.MDC_KEY, trace.requestId)) {
            AiNoteEditService.StreamPreview preview =
                    aiNoteEditService.streamPreview(userId, noteId, request.capability());
            if (closed.get()) {
                return;
            }

            trace.generationStartedAt.set(System.nanoTime());
            Disposable subscription = preview.content().subscribe(
                    text -> withRequestId(trace.requestId, () -> {
                        if (text == null || text.isEmpty()) {
                            return;
                        }
                        trace.firstTokenMs.compareAndSet(-1, elapsedMillis(trace.startedAt));
                        if (!sendEvent(emitter, "delta", Map.of("text", text))) {
                            closed.set(true);
                            dispose(subscriptionRef);
                            trace.releasePermit();
                            emitter.complete();
                        }
                    }),
                    error -> withRequestId(trace.requestId,
                            () -> completeWithError(emitter, error, trace)),
                    () -> withRequestId(trace.requestId, () -> {
                        sendEvent(emitter, "done", Map.of(
                                "requestId", trace.requestId,
                                "contentHash", preview.contentHash(),
                                "capability", request.capability()));
                        if (trace.terminalLogged.compareAndSet(false, true)) {
                            metricsService.recordCompleted(elapsedMillis(trace.startedAt));
                            log.info("event=ai.note.edit.completed userId={} noteId={} capability={} firstTokenMs={} totalMs={}",
                                    userId, noteId, request.capability().value(),
                                    trace.firstTokenMs.get(), elapsedMillis(trace.startedAt));
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
            // 这个 catch 在 try-with-resources 之外执行，MDC 已被清空，必须重新挂上 requestId，
            // 否则终端失败日志会丢掉错误编号，客户端拿到的编号就和服务端日志对不上。
            withRequestId(trace.requestId, () -> completeWithError(emitter, error, trace));
        }
    }

    private void completeWithError(SseEmitter emitter, Throwable error, EditTrace trace) {
        long totalMs = elapsedMillis(trace.startedAt);
        String outcome = classifyOutcome(error);
        if (trace.terminalLogged.compareAndSet(false, true)) {
            if (OUTCOME_TIMEOUT.equals(outcome)) {
                metricsService.recordTimeout();
            }
            else if (OUTCOME_QUEUE_REJECTED.equals(outcome)) {
                metricsService.recordQueueRejected();
            }
            metricsService.recordFailed(totalMs);
            log.error("event=ai.note.edit.failed userId={} noteId={} outcome={} totalMs={}",
                    trace.userId, trace.noteId, outcome, totalMs, error);
        }
        sendEvent(emitter, "error", Map.of(
                "message", resolveErrorMessage(error),
                "errorId", trace.requestId));
        trace.releasePermit();
        emitter.complete();
    }

    private static final String OUTCOME_TIMEOUT = "timeout";
    private static final String OUTCOME_QUEUE_REJECTED = "queue_rejected";

    /**
     * 只覆盖 AI 编辑会遇到的失败类型。
     * 这里刻意不照搬问答链路的错误分类 —— 那些 429 / 401 / 403 分支是 RAG 检索特有的。
     */
    private String classifyOutcome(Throwable error) {
        if (hasCause(error, SocketTimeoutException.class)
                || hasCause(error, java.net.http.HttpTimeoutException.class)
                || hasCause(error, TimeoutException.class)) {
            return OUTCOME_TIMEOUT;
        }
        if (hasCause(error, RejectedExecutionException.class)) {
            return OUTCOME_QUEUE_REJECTED;
        }
        return "failed";
    }

    private String resolveErrorMessage(Throwable error) {
        if (OUTCOME_TIMEOUT.equals(classifyOutcome(error))) {
            return "AI 生成超时，请稍后重试";
        }
        if (OUTCOME_QUEUE_REJECTED.equals(classifyOutcome(error))) {
            return "AI 当前请求较多，请稍后重试";
        }
        if (error instanceof com.notepad.common.BusinessException businessException) {
            return businessException.getMessage();
        }
        return "AI 生成失败，请稍后重试";
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
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestTraceContext.MDC_KEY, requestId)) {
            action.run();
        }
    }

    private long elapsedMillis(long startedAt) {
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
        return elapsed > 0 ? elapsed : 0L;
    }

    /**
     * 一次预览的终止状态。计数与日志共用 terminalLogged，
     * 保证 emitter.complete() 之后 onCompletion 再触发时不会重复统计。
     */
    private static final class EditTrace {

        private final String requestId;
        private final Long userId;
        private final Long noteId;
        private final long startedAt = System.nanoTime();
        private final java.util.concurrent.atomic.AtomicLong firstTokenMs =
                new java.util.concurrent.atomic.AtomicLong(-1);
        private final java.util.concurrent.atomic.AtomicLong generationStartedAt =
                new java.util.concurrent.atomic.AtomicLong(-1);
        private final AtomicBoolean terminalLogged = new AtomicBoolean(false);
        private volatile AiConcurrencyLimiter.Permit permit;

        private EditTrace(String requestId, Long userId, Long noteId) {
            this.requestId = requestId;
            this.userId = userId;
            this.noteId = noteId;
        }

        private void releasePermit() {
            AiConcurrencyLimiter.Permit current = permit;
            if (current != null) {
                current.close();
            }
        }
    }
}
