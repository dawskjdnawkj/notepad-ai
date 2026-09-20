package com.notepad.ai.controller;

import com.notepad.ai.dto.AiChatRequest;
import com.notepad.ai.dto.AiConcurrencyStatusResponse;
import com.notepad.ai.dto.AiMetricsResponse;
import com.notepad.ai.dto.EmbeddingTestRequest;
import com.notepad.ai.dto.EmbeddingTestResponse;
import com.notepad.ai.dto.NoteBatchIndexResponse;
import com.notepad.ai.dto.NoteIndexBackupItem;
import com.notepad.ai.dto.NoteIndexBackupVerifyResponse;
import com.notepad.ai.dto.NoteIndexResponse;
import com.notepad.ai.dto.NoteIndexRestoreResponse;
import com.notepad.ai.dto.NoteIndexStatusResponse;
import com.notepad.ai.dto.NoteSearchItem;
import com.notepad.ai.dto.NoteSearchRequest;
import com.notepad.ai.dto.RagAnswerRequest;
import com.notepad.ai.dto.RagAnswerResponse;
import com.notepad.ai.service.AiChatService;
import com.notepad.ai.service.AiConcurrencyLimiter;
import com.notepad.ai.service.AiEmbeddingService;
import com.notepad.ai.service.AiMetricsService;
import com.notepad.ai.service.NoteVectorService;
import com.notepad.ai.service.RagAnswerService;
import com.notepad.common.Result;
import com.notepad.common.BusinessException;
import com.notepad.common.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiChatService aiChatService;
    private final AiEmbeddingService aiEmbeddingService;
    private final NoteVectorService noteVectorService;
    private final RagAnswerService ragAnswerService;
    private final AiConcurrencyLimiter concurrencyLimiter;
    private final AiMetricsService metricsService;

    @PostMapping("/chat")
    public Result<String> chat(
            @Valid @RequestBody AiChatRequest request,
            HttpServletResponse response
    ) {
        AiConcurrencyLimiter.Permit permit = acquireOrReject(response, "chat");
        long startedAt = System.nanoTime();
        try {
            String answer = aiChatService.chat(request.message());
            metricsService.recordCompleted(elapsedMillis(startedAt));
            return Result.ok(answer);
        }
        catch (RuntimeException exception) {
            metricsService.recordFailed(elapsedMillis(startedAt));
            throw exception;
        }
        finally {
            permit.close();
        }
    }

    @PostMapping("/embedding/test")
    public Result<EmbeddingTestResponse> testEmbedding(
            @Valid @RequestBody EmbeddingTestRequest request
    ) {
        return Result.ok(aiEmbeddingService.compare(request));
    }

    @PostMapping("/notes/{noteId}/index")
    public Result<NoteIndexResponse> indexNote(@PathVariable Long noteId) {
        return Result.ok(noteVectorService.indexNote(UserContext.getUserId(), noteId));
    }

    @PostMapping("/notes/index-all")
    public Result<NoteBatchIndexResponse> indexAllNotes() {
        return Result.ok(noteVectorService.indexAllNotes(UserContext.getUserId()));
    }

    @GetMapping("/notes/index/status")
    public Result<NoteIndexStatusResponse> noteIndexStatus() {
        return Result.ok(noteVectorService.indexStatus(UserContext.getUserId()));
    }

    @PostMapping("/notes/index/rebuild")
    public Result<NoteBatchIndexResponse> rebuildNoteIndex() {
        return Result.ok(noteVectorService.rebuildUserIndex(UserContext.getUserId()));
    }

    @GetMapping("/notes/index/backups")
    public Result<List<NoteIndexBackupItem>> listIndexBackups() {
        return Result.ok(noteVectorService.listIndexBackups(UserContext.getUserId()));
    }

    @PostMapping("/notes/index/backups")
    public Result<NoteIndexBackupItem> createIndexBackup() {
        return Result.ok(noteVectorService.createIndexBackup(UserContext.getUserId()));
    }

    @GetMapping("/notes/index/backups/{fileName}/verify")
    public Result<NoteIndexBackupVerifyResponse> verifyIndexBackup(@PathVariable String fileName) {
        return Result.ok(noteVectorService.verifyIndexBackup(UserContext.getUserId(), fileName));
    }

    @PostMapping("/notes/index/backups/{fileName}/restore")
    public Result<NoteIndexRestoreResponse> restoreIndexBackup(@PathVariable String fileName) {
        return Result.ok(noteVectorService.restoreIndexBackup(UserContext.getUserId(), fileName));
    }

    /**
     * 只读的 AI 请求统计。纯内存计数，服务重启后归零；
     * 每个计数点同时都会打 event=... 结构化日志，历史趋势可对日志聚合。
     */
    @GetMapping("/metrics")
    public Result<AiMetricsResponse> metrics() {
        AiMetricsService.Snapshot snapshot = metricsService.snapshot();
        return Result.ok(new AiMetricsResponse(
                snapshot.rejectedStream(),
                snapshot.rejectedBlocking(),
                snapshot.rejectedQueue(),
                snapshot.timeout(),
                snapshot.retry(),
                snapshot.completed(),
                snapshot.failed(),
                snapshot.durationDistribution()));
    }

    /**
     * 只读的并发限制器状态。静止时刻 activeCount + availableGlobalPermits 必须等于 globalMax，
     * 否则说明有请求路径泄漏了并发许可。
     */
    @GetMapping("/concurrency/status")
    public Result<AiConcurrencyStatusResponse> concurrencyStatus() {
        return Result.ok(new AiConcurrencyStatusResponse(
                concurrencyLimiter.activeCount(),
                concurrencyLimiter.availableGlobalPermits(),
                concurrencyLimiter.globalMax(),
                concurrencyLimiter.perUserMax()));
    }

    @PostMapping("/notes/search")
    public Result<List<NoteSearchItem>> searchNotes(
            @Valid @RequestBody NoteSearchRequest request
    ) {
        return Result.ok(noteVectorService.search(UserContext.getUserId(), request.question()));
    }

    @PostMapping("/notes/ask")
    public Result<RagAnswerResponse> askNotes(
            @Valid @RequestBody RagAnswerRequest request,
            HttpServletResponse response
    ) {
        AiConcurrencyLimiter.Permit permit = acquireOrReject(response, "notes/ask");
        long startedAt = System.nanoTime();
        try {
            RagAnswerResponse answer = ragAnswerService.answer(
                    UserContext.getUserId(),
                    request.question(),
                    request.history(),
                    request.noteId(),
                    request.notebookId());
            metricsService.recordCompleted(elapsedMillis(startedAt));
            return Result.ok(answer);
        }
        catch (RuntimeException exception) {
            metricsService.recordFailed(elapsedMillis(startedAt));
            throw exception;
        }
        finally {
            permit.close();
        }
    }

    /**
     * 阻塞式接口的并发许可获取。
     * 被拒绝时这里原先完全没有日志，限流拒绝数无从统计，所以补上计数与结构化日志。
     */
    private AiConcurrencyLimiter.Permit acquireOrReject(HttpServletResponse response, String endpoint) {
        Long userId = UserContext.getUserId();
        AiConcurrencyLimiter.Permit permit = concurrencyLimiter.tryAcquire(userId);
        if (permit == null) {
            response.setHeader("Retry-After", "2");
            metricsService.recordBlockingRejected();
            log.warn("event=ai.request.rejected userId={} endpoint={} outcome=concurrency_limited activeCount={}",
                    userId, endpoint, concurrencyLimiter.activeCount());
            throw new BusinessException(429, "AI 当前请求较多，请稍后重试");
        }
        return permit;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
