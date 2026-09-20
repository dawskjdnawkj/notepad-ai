package com.notepad.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notepad.ai.dto.NoteSearchItem;
import com.notepad.ai.dto.NoteSearchResult;
import com.notepad.ai.dto.RagEvalCaseRequest;
import com.notepad.ai.dto.RagEvalCaseResponse;
import com.notepad.ai.dto.RagEvalResultItem;
import com.notepad.ai.dto.RagEvalRunResponse;
import com.notepad.ai.dto.RagEvalRunSummaryResponse;
import com.notepad.common.BusinessException;
import com.notepad.entity.AiRagEvalCase;
import com.notepad.entity.AiRagEvalRun;
import com.notepad.entity.AiAnswerFeedback;
import com.notepad.entity.Note;
import com.notepad.entity.Notebook;
import com.notepad.mapper.AiRagEvalCaseMapper;
import com.notepad.mapper.AiRagEvalRunMapper;
import com.notepad.mapper.AiAnswerFeedbackMapper;
import com.notepad.mapper.NoteMapper;
import com.notepad.mapper.NotebookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagEvaluationService {

    private static final int MAX_CASES_PER_USER = 100;
    private static final int MAX_CASES_PER_RUN = 30;
    private static final int MAX_RUN_HISTORY = 10;
    private static final String MATCH_ANY = "any";
    private static final String MATCH_ALL = "all";
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final TypeReference<List<Long>> ID_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<RagEvalResultItem>> RESULT_LIST_TYPE =
            new TypeReference<>() { };

    private final AiRagEvalCaseMapper caseMapper;
    private final AiRagEvalRunMapper runMapper;
    private final AiAnswerFeedbackMapper feedbackMapper;
    private final NoteMapper noteMapper;
    private final NotebookMapper notebookMapper;
    private final NoteVectorService noteVectorService;
    private final ObjectMapper objectMapper;

    public List<RagEvalCaseResponse> listCases(Long userId) {
        return caseMapper.selectList(new LambdaQueryWrapper<AiRagEvalCase>()
                        .eq(AiRagEvalCase::getUserId, userId)
                        .orderByDesc(AiRagEvalCase::getUpdateTime))
                .stream()
                .map(this::toCaseResponse)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public RagEvalCaseResponse createCase(Long userId, RagEvalCaseRequest request) {
        ensureCaseLimit(userId);

        ValidatedCase validated = validateCase(userId, request);
        AiRagEvalCase entity = insertCase(userId, request, validated);
        return toCaseResponse(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public RagEvalCaseResponse createCaseFromFeedback(
            Long userId,
            String conversationClientId,
            String messageClientId,
            RagEvalCaseRequest request) {
        validateClientId(conversationClientId, "会话 ID 格式不正确");
        validateClientId(messageClientId, "消息 ID 格式不正确");

        AiAnswerFeedback feedback = feedbackMapper.selectOne(
                new LambdaQueryWrapper<AiAnswerFeedback>()
                        .eq(AiAnswerFeedback::getUserId, userId)
                        .eq(AiAnswerFeedback::getConversationClientId, conversationClientId)
                        .eq(AiAnswerFeedback::getMessageClientId, messageClientId)
                        .last("FOR UPDATE"));
        if (feedback == null || !"unhelpful".equals(feedback.getRating())) {
            throw new BusinessException(404, "没有找到可转换的没帮助反馈");
        }

        if (feedback.getEvalCaseId() != null) {
            AiRagEvalCase existing = caseMapper.selectOne(
                    new LambdaQueryWrapper<AiRagEvalCase>()
                            .eq(AiRagEvalCase::getId, feedback.getEvalCaseId())
                            .eq(AiRagEvalCase::getUserId, userId));
            if (existing != null) {
                return toCaseResponse(existing);
            }
            feedback.setEvalCaseId(null);
        }

        ensureCaseLimit(userId);
        ValidatedCase validated = validateCase(userId, request);
        AiRagEvalCase entity = insertCase(userId, request, validated);
        feedback.setEvalCaseId(entity.getId());
        feedback.setUpdateTime(LocalDateTime.now());
        feedbackMapper.updateById(feedback);
        return toCaseResponse(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public RagEvalCaseResponse updateCase(Long userId, Long caseId, RagEvalCaseRequest request) {
        AiRagEvalCase entity = requireOwnedCase(userId, caseId);
        ValidatedCase validated = validateCase(userId, request);
        applyRequest(entity, request, validated.expectedNoteIds(), validated.matchMode());
        entity.setUpdateTime(LocalDateTime.now());
        caseMapper.updateById(entity);
        return toCaseResponse(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long userId, Long caseId) {
        requireOwnedCase(userId, caseId);
        feedbackMapper.update(null, new LambdaUpdateWrapper<AiAnswerFeedback>()
                .eq(AiAnswerFeedback::getUserId, userId)
                .eq(AiAnswerFeedback::getEvalCaseId, caseId)
                .set(AiAnswerFeedback::getEvalCaseId, null));
        caseMapper.delete(new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getId, caseId)
                .eq(AiRagEvalCase::getUserId, userId));
    }

    private void ensureCaseLimit(Long userId) {
        Long count = caseMapper.selectCount(new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getUserId, userId));
        if (count != null && count >= MAX_CASES_PER_USER) {
            throw new BusinessException(400, "每个用户最多创建 100 个回归测试用例");
        }
    }

    private AiRagEvalCase insertCase(
            Long userId,
            RagEvalCaseRequest request,
            ValidatedCase validated) {
        LocalDateTime now = LocalDateTime.now();
        AiRagEvalCase entity = new AiRagEvalCase();
        entity.setUserId(userId);
        applyRequest(entity, request, validated.expectedNoteIds(), validated.matchMode());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        caseMapper.insert(entity);
        return entity;
    }

    private void validateClientId(String value, String message) {
        if (value == null || !CLIENT_ID_PATTERN.matcher(value).matches()) {
            throw new BusinessException(400, message);
        }
    }

    public List<RagEvalRunSummaryResponse> listRuns(Long userId) {
        return runMapper.selectList(new LambdaQueryWrapper<AiRagEvalRun>()
                        .eq(AiRagEvalRun::getUserId, userId)
                        .orderByDesc(AiRagEvalRun::getCreateTime)
                        .last("LIMIT " + MAX_RUN_HISTORY))
                .stream()
                .map(this::toRunSummary)
                .toList();
    }

    public RagEvalRunResponse runDetail(Long userId, Long runId) {
        AiRagEvalRun run = runMapper.selectOne(new LambdaQueryWrapper<AiRagEvalRun>()
                .eq(AiRagEvalRun::getId, runId)
                .eq(AiRagEvalRun::getUserId, userId));
        if (run == null) {
            throw new BusinessException(404, "回归测试报告不存在");
        }
        return toRunResponse(run, readResults(run.getResultsJson()));
    }

    public RagEvalRunResponse runEnabledCases(Long userId) {
        long runStartedAt = System.nanoTime();
        List<AiRagEvalCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalCase>()
                        .eq(AiRagEvalCase::getUserId, userId)
                        .eq(AiRagEvalCase::getEnabled, 1)
                        .orderByAsc(AiRagEvalCase::getId));
        if (cases.isEmpty()) {
            throw new BusinessException(400, "请先创建并启用至少一个测试用例");
        }
        if (cases.size() > MAX_CASES_PER_RUN) {
            throw new BusinessException(400, "单次最多运行 30 个启用用例");
        }

        List<RagEvalResultItem> results = new ArrayList<>(cases.size());
        int passedCount = 0;
        int positiveCaseCount = 0;
        int hitCount = 0;
        int negativeCaseCount = 0;
        int correctRejectionCount = 0;
        int wrongReferenceCount = 0;
        long totalDurationMs = 0;

        for (AiRagEvalCase testCase : cases) {
            RagEvalResultItem result = runCase(userId, testCase);
            results.add(result);
            totalDurationMs += result.durationMs();
            wrongReferenceCount += result.wrongReferenceCount();
            if (result.passed()) passedCount++;
            if (result.expectAnswer()) {
                positiveCaseCount++;
                if (meetsExpectedHitRequirement(testCase, result)) hitCount++;
            } else {
                negativeCaseCount++;
                if (result.passed()) correctRejectionCount++;
            }
        }

        AiRagEvalRun run = new AiRagEvalRun();
        run.setUserId(userId);
        run.setCaseCount(cases.size());
        run.setPassedCount(passedCount);
        run.setPositiveCaseCount(positiveCaseCount);
        run.setHitCount(hitCount);
        run.setNegativeCaseCount(negativeCaseCount);
        run.setCorrectRejectionCount(correctRejectionCount);
        run.setWrongReferenceCount(wrongReferenceCount);
        run.setAverageDurationMs((int) (totalDurationMs / cases.size()));
        run.setResultsJson(writeJson(results));
        run.setCreateTime(LocalDateTime.now());
        runMapper.insert(run);
        log.info(
                "event=rag.evaluation.completed userId={} runId={} caseCount={} passedCount={} wrongReferenceCount={} averageRetrievalMs={} totalMs={}",
                userId,
                run.getId(),
                cases.size(),
                passedCount,
                wrongReferenceCount,
                run.getAverageDurationMs(),
                elapsedMillis(runStartedAt));
        return toRunResponse(run, results);
    }

    private RagEvalResultItem runCase(Long userId, AiRagEvalCase testCase) {
        long startedAt = System.nanoTime();
        List<Long> expectedNoteIds = readIds(testCase.getExpectedNoteIdsJson());
        try {
            Long noteId = "note".equals(testCase.getScopeType()) ? testCase.getScopeId() : null;
            Long notebookId = "notebook".equals(testCase.getScopeType()) ? testCase.getScopeId() : null;
            NoteSearchResult searchResult = noteVectorService.searchWithTrace(
                    userId,
                    testCase.getQuestion(),
                    noteId,
                    notebookId);
            List<NoteSearchItem> matches = searchResult.items();
            double minScore = testCase.getMinScore().doubleValue();
            LinkedHashSet<Long> relevantMatches = new LinkedHashSet<>();
            Double topScore = null;
            for (NoteSearchItem match : matches) {
                if (match.score() != null && (topScore == null || match.score() > topScore)) {
                    topScore = match.score();
                }
                if (match.score() != null && match.score() >= minScore) {
                    relevantMatches.add(match.noteId());
                }
            }

            Set<Long> expectedSet = new HashSet<>(expectedNoteIds);
            long expectedHitCount = relevantMatches.stream().filter(expectedSet::contains).count();
            int wrongCount = (int) relevantMatches.stream().filter(id -> !expectedSet.contains(id)).count();
            boolean expectAnswer = testCase.getExpectAnswer() == 1;
            boolean passed;
            String failureReason = null;
            if (expectAnswer) {
                boolean expectedMatched = MATCH_ALL.equals(normalizeMatchMode(testCase.getMatchMode()))
                        ? expectedHitCount == expectedSet.size()
                        : expectedHitCount > 0;
                passed = expectedMatched && wrongCount == 0;
                List<String> failureReasons = new ArrayList<>(2);
                if (!expectedMatched) {
                    failureReasons.add(expectedHitCount > 0
                            ? "未命中全部预期笔记"
                            : "未在最低相关度以上命中预期笔记");
                }
                if (wrongCount > 0) {
                    failureReasons.add("同时命中了非预期笔记");
                }
                if (!failureReasons.isEmpty()) {
                    failureReason = String.join("；", failureReasons);
                }
            } else {
                passed = relevantMatches.isEmpty();
                if (!passed) failureReason = "本应拒答，但检索到了相关片段";
            }

            return new RagEvalResultItem(
                    testCase.getId(),
                    testCase.getName(),
                    testCase.getQuestion(),
                    expectAnswer,
                    expectedNoteIds,
                    List.copyOf(relevantMatches),
                    topScore,
                    wrongCount,
                    elapsedMillis(startedAt),
                    passed,
                    failureReason,
                    searchResult.traces(),
                    null);
        } catch (RuntimeException exception) {
            log.warn("RAG 回归用例执行失败: userId={}, caseId={}", userId, testCase.getId(), exception);
            return new RagEvalResultItem(
                    testCase.getId(),
                    testCase.getName(),
                    testCase.getQuestion(),
                    testCase.getExpectAnswer() == 1,
                    expectedNoteIds,
                    List.of(),
                    null,
                    0,
                    elapsedMillis(startedAt),
                    false,
                    "执行失败，请查看后端日志",
                    List.of(),
                    null);
        }
    }

    private boolean meetsExpectedHitRequirement(
            AiRagEvalCase testCase,
            RagEvalResultItem result) {
        Set<Long> expected = new HashSet<>(result.expectedNoteIds());
        long hitCount = result.matchedNoteIds().stream().filter(expected::contains).count();
        return MATCH_ALL.equals(normalizeMatchMode(testCase.getMatchMode()))
                ? !expected.isEmpty() && hitCount == expected.size()
                : hitCount > 0;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private ValidatedCase validateCase(Long userId, RagEvalCaseRequest request) {
        boolean allScope = "all".equals(request.scopeType());
        if (allScope && request.scopeId() != null) {
            throw new BusinessException(400, "全部笔记范围不能指定范围 ID");
        }
        if (!allScope && request.scopeId() == null) {
            throw new BusinessException(400, "当前笔记或笔记本范围必须指定范围 ID");
        }

        if ("note".equals(request.scopeType())) {
            requireOwnedNote(userId, request.scopeId());
        } else if ("notebook".equals(request.scopeType())) {
            Notebook notebook = notebookMapper.selectById(request.scopeId());
            if (notebook == null || !userId.equals(notebook.getUserId())) {
                throw new BusinessException(404, "笔记本不存在");
            }
        }

        List<Long> expectedIds = request.expectedNoteIds() == null
                ? List.of()
                : request.expectedNoteIds().stream().distinct().toList();
        if (request.expectAnswer() && expectedIds.isEmpty()) {
            throw new BusinessException(400, "应命中用例至少需要选择一篇预期笔记");
        }
        if (!request.expectAnswer() && !expectedIds.isEmpty()) {
            throw new BusinessException(400, "应拒答用例不能配置预期笔记");
        }

        String matchMode = request.expectAnswer()
                ? normalizeMatchMode(request.matchMode())
                : MATCH_ANY;

        for (Long expectedId : expectedIds) {
            Note note = requireOwnedNote(userId, expectedId);
            if ("note".equals(request.scopeType()) && !expectedId.equals(request.scopeId())) {
                throw new BusinessException(400, "预期笔记必须与当前笔记范围一致");
            }
            if ("notebook".equals(request.scopeType())
                    && !request.scopeId().equals(note.getNotebookId())) {
                throw new BusinessException(400, "预期笔记必须属于指定笔记本");
            }
        }
        return new ValidatedCase(expectedIds, matchMode);
    }

    private Note requireOwnedNote(Long userId, Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !userId.equals(note.getUserId())) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    private AiRagEvalCase requireOwnedCase(Long userId, Long caseId) {
        AiRagEvalCase entity = caseMapper.selectOne(new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getId, caseId)
                .eq(AiRagEvalCase::getUserId, userId));
        if (entity == null) {
            throw new BusinessException(404, "回归测试用例不存在");
        }
        return entity;
    }

    private void applyRequest(
            AiRagEvalCase entity,
            RagEvalCaseRequest request,
            List<Long> expectedNoteIds,
            String matchMode) {
        entity.setName(request.name().trim());
        entity.setQuestion(request.question().trim());
        entity.setScopeType(request.scopeType());
        entity.setScopeId(request.scopeId());
        entity.setExpectAnswer(request.expectAnswer() ? 1 : 0);
        entity.setExpectedNoteIdsJson(writeJson(expectedNoteIds));
        entity.setMatchMode(matchMode);
        entity.setMinScore(BigDecimal.valueOf(request.minScore()));
        entity.setEnabled(request.enabled() ? 1 : 0);
    }

    private RagEvalCaseResponse toCaseResponse(AiRagEvalCase entity) {
        return new RagEvalCaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getQuestion(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getExpectAnswer() == 1,
                readIds(entity.getExpectedNoteIdsJson()),
                normalizeMatchMode(entity.getMatchMode()),
                entity.getMinScore().doubleValue(),
                entity.getEnabled() == 1,
                entity.getUpdateTime());
    }

    private RagEvalRunSummaryResponse toRunSummary(AiRagEvalRun run) {
        return new RagEvalRunSummaryResponse(
                run.getId(),
                run.getCaseCount(),
                run.getPassedCount(),
                rate(run.getPassedCount(), run.getCaseCount()),
                rate(run.getHitCount(), run.getPositiveCaseCount()),
                rate(run.getCorrectRejectionCount(), run.getNegativeCaseCount()),
                run.getWrongReferenceCount(),
                run.getAverageDurationMs(),
                run.getCreateTime());
    }

    private RagEvalRunResponse toRunResponse(AiRagEvalRun run, List<RagEvalResultItem> results) {
        // 在「读取报告」时回填来源反馈，而不是在「跑回归」时写入，这样本次改动
        // 之前已经跑出来的历史报告同样可以追溯到原始反馈。
        Map<Long, RagEvalResultItem.SourceFeedback> sourceFeedbackByCaseId =
                loadSourceFeedback(run.getUserId(), results);
        List<RagEvalResultItem> enrichedResults = results.stream()
                .map(item -> withSourceFeedback(item, sourceFeedbackByCaseId.get(item.caseId())))
                .toList();

        return new RagEvalRunResponse(
                run.getId(),
                run.getCaseCount(),
                run.getPassedCount(),
                rate(run.getPassedCount(), run.getCaseCount()),
                run.getPositiveCaseCount(),
                run.getHitCount(),
                rate(run.getHitCount(), run.getPositiveCaseCount()),
                run.getNegativeCaseCount(),
                run.getCorrectRejectionCount(),
                rate(run.getCorrectRejectionCount(), run.getNegativeCaseCount()),
                run.getWrongReferenceCount(),
                run.getAverageDurationMs(),
                run.getCreateTime(),
                enrichedResults);
    }

    /**
     * 批量查出这些用例分别由哪条「没帮助」反馈转换而来。
     * 走 ai_answer_feedback 的 uk_user_eval_case (user_id, eval_case_id) 索引，一次查询，无 N+1。
     */
    private Map<Long, RagEvalResultItem.SourceFeedback> loadSourceFeedback(
            Long userId, List<RagEvalResultItem> results) {
        if (userId == null || results.isEmpty()) {
            return Map.of();
        }
        List<Long> caseIds = results.stream()
                .map(RagEvalResultItem::caseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (caseIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, RagEvalResultItem.SourceFeedback> byCaseId = new HashMap<>();
        for (AiAnswerFeedback feedback : feedbackMapper.selectList(
                new LambdaQueryWrapper<AiAnswerFeedback>()
                        .eq(AiAnswerFeedback::getUserId, userId)
                        .in(AiAnswerFeedback::getEvalCaseId, caseIds))) {
            byCaseId.putIfAbsent(feedback.getEvalCaseId(),
                    new RagEvalResultItem.SourceFeedback(
                            feedback.getId(),
                            feedback.getReason(),
                            feedback.getComment(),
                            feedback.getUpdateTime()));
        }
        return byCaseId;
    }

    private RagEvalResultItem withSourceFeedback(
            RagEvalResultItem item, RagEvalResultItem.SourceFeedback sourceFeedback) {
        if (sourceFeedback == null) {
            return item;
        }
        return new RagEvalResultItem(
                item.caseId(),
                item.name(),
                item.question(),
                item.expectAnswer(),
                item.expectedNoteIds(),
                item.matchedNoteIds(),
                item.topScore(),
                item.wrongReferenceCount(),
                item.durationMs(),
                item.passed(),
                item.failureReason(),
                item.retrievalTraces(),
                sourceFeedback);
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private List<Long> readIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, ID_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            log.warn("RAG 回归用例中的预期笔记 ID 无法解析", exception);
            return List.of();
        }
    }

    private List<RagEvalResultItem> readResults(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, RESULT_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "回归测试报告数据无法解析");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "回归测试数据保存失败");
        }
    }

    private String normalizeMatchMode(String matchMode) {
        return MATCH_ALL.equals(matchMode) ? MATCH_ALL : MATCH_ANY;
    }

    private record ValidatedCase(List<Long> expectedNoteIds, String matchMode) {
    }
}
