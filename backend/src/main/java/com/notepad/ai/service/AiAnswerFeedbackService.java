package com.notepad.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notepad.ai.dto.AiAnswerFeedbackRequest;
import com.notepad.ai.dto.AiAnswerFeedbackResponse;
import com.notepad.ai.dto.AiFeedbackStatisticsResponse;
import com.notepad.ai.dto.RagEvalResultItem;
import com.notepad.common.BusinessException;
import com.notepad.entity.AiAnswerFeedback;
import com.notepad.entity.AiConversation;
import com.notepad.entity.AiConversationMessageEntity;
import com.notepad.entity.AiRagEvalRun;
import com.notepad.mapper.AiAnswerFeedbackMapper;
import com.notepad.mapper.AiConversationMapper;
import com.notepad.mapper.AiConversationMessageMapper;
import com.notepad.mapper.AiRagEvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnswerFeedbackService {

    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final List<String> FEEDBACK_REASONS = List.of(
            "irrelevant_sources",
            "incomplete",
            "inconsistent",
            "wrong_scope",
            "other");
    private static final int COMMON_FAILURE_LIMIT = 10;
    private static final String EVAL_STATUS_PASSED = "passed";
    private static final String EVAL_STATUS_FAILED = "failed";
    private static final String EVAL_STATUS_NOT_RUN = "not_run";
    private static final TypeReference<List<RagEvalResultItem>> RESULT_LIST_TYPE =
            new TypeReference<>() { };

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiAnswerFeedbackMapper feedbackMapper;
    private final AiRagEvalRunMapper runMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public AiAnswerFeedbackResponse save(
            Long userId,
            String conversationClientId,
            String messageClientId,
            AiAnswerFeedbackRequest request) {
        validateId(conversationClientId, "会话 ID 格式不正确");
        validateId(messageClientId, "消息 ID 格式不正确");

        AiConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .eq(AiConversation::getClientId, conversationClientId));
        if (conversation == null) {
            throw new BusinessException(404, "AI 会话不存在");
        }

        AiConversationMessageEntity message = messageMapper.selectOne(
                new LambdaQueryWrapper<AiConversationMessageEntity>()
                        .eq(AiConversationMessageEntity::getConversationId, conversation.getId())
                        .eq(AiConversationMessageEntity::getUserId, userId)
                        .eq(AiConversationMessageEntity::getClientMessageId, messageClientId)
                        .eq(AiConversationMessageEntity::getRole, "assistant"));
        if (message == null) {
            throw new BusinessException(404, "AI 回答不存在");
        }

        String rating = request.rating();
        String reason = normalize(request.reason());
        String comment = normalize(request.comment());
        if ("unhelpful".equals(rating) && reason == null) {
            throw new BusinessException(400, "请选择回答没有帮助的原因");
        }
        if ("helpful".equals(rating)) {
            reason = null;
            comment = null;
        }

        AiAnswerFeedback feedback = feedbackMapper.selectOne(
                new LambdaQueryWrapper<AiAnswerFeedback>()
                        .eq(AiAnswerFeedback::getUserId, userId)
                        .eq(AiAnswerFeedback::getConversationClientId, conversationClientId)
                        .eq(AiAnswerFeedback::getMessageClientId, messageClientId));
        LocalDateTime now = LocalDateTime.now();
        if (feedback == null) {
            feedback = new AiAnswerFeedback();
            feedback.setUserId(userId);
            feedback.setConversationClientId(conversationClientId);
            feedback.setMessageClientId(messageClientId);
            feedback.setCreateTime(now);
        }

        feedback.setRating(rating);
        feedback.setReason(reason);
        feedback.setComment(comment);
        feedback.setQuestion(message.getQuestion() == null ? "" : message.getQuestion());
        feedback.setAnswer(message.getContent());
        feedback.setSourcesJson(message.getSourcesJson());
        feedback.setScopeType(message.getScopeType() == null ? "all" : message.getScopeType());
        feedback.setScopeId(message.getScopeId());
        feedback.setUpdateTime(now);

        if (feedback.getId() == null) {
            feedbackMapper.insert(feedback);
        } else {
            feedbackMapper.updateById(feedback);
        }
        return toResponse(feedback);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String conversationClientId, String messageClientId) {
        validateId(conversationClientId, "会话 ID 格式不正确");
        validateId(messageClientId, "消息 ID 格式不正确");
        feedbackMapper.delete(new LambdaQueryWrapper<AiAnswerFeedback>()
                .eq(AiAnswerFeedback::getUserId, userId)
                .eq(AiAnswerFeedback::getConversationClientId, conversationClientId)
                .eq(AiAnswerFeedback::getMessageClientId, messageClientId));
    }

    public AiFeedbackStatisticsResponse statistics(Long userId) {
        List<AiAnswerFeedback> feedbackList = feedbackMapper.selectList(
                new LambdaQueryWrapper<AiAnswerFeedback>()
                        .select(
                                AiAnswerFeedback::getRating,
                                AiAnswerFeedback::getReason,
                                AiAnswerFeedback::getQuestion,
                                AiAnswerFeedback::getEvalCaseId,
                                AiAnswerFeedback::getUpdateTime)
                        .eq(AiAnswerFeedback::getUserId, userId));

        long helpfulCount = feedbackList.stream()
                .filter(feedback -> "helpful".equals(feedback.getRating()))
                .count();
        List<AiAnswerFeedback> unhelpfulFeedback = feedbackList.stream()
                .filter(feedback -> "unhelpful".equals(feedback.getRating()))
                .toList();
        long unhelpfulCount = unhelpfulFeedback.size();
        long convertedCaseCount = unhelpfulFeedback.stream()
                .filter(feedback -> feedback.getEvalCaseId() != null)
                .count();

        Map<String, Long> reasonTotals = new LinkedHashMap<>();
        FEEDBACK_REASONS.forEach(reason -> reasonTotals.put(reason, 0L));
        Map<String, QuestionAccumulator> questionTotals = new LinkedHashMap<>();
        for (AiAnswerFeedback feedback : unhelpfulFeedback) {
            String reason = normalizeReason(feedback.getReason());
            reasonTotals.compute(reason, (key, count) -> count == null ? 1L : count + 1L);

            String question = normalizeQuestion(feedback.getQuestion());
            questionTotals.computeIfAbsent(question, QuestionAccumulator::new)
                    .add(reason, feedback.getEvalCaseId(), feedback.getUpdateTime());
        }

        List<AiFeedbackStatisticsResponse.ReasonCount> reasonCounts = reasonTotals.entrySet()
                .stream()
                .map(entry -> new AiFeedbackStatisticsResponse.ReasonCount(
                        entry.getKey(),
                        entry.getValue(),
                        ratio(entry.getValue(), unhelpfulCount)))
                .toList();

        // 把反馈与回归测试报告关联起来：每个高频问题下已转化的用例，
        // 在最近一次回归报告里是通过、失败还是没跑过。
        LatestRun latestRun = loadLatestRun(userId);

        List<AiFeedbackStatisticsResponse.CommonFailureQuestion> commonFailureQuestions =
                questionTotals.values().stream()
                        .sorted(Comparator.comparingLong(QuestionAccumulator::count).reversed()
                                .thenComparing(
                                        QuestionAccumulator::lastFeedbackAt,
                                        Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(QuestionAccumulator::question))
                        .limit(COMMON_FAILURE_LIMIT)
                        .map(accumulator -> accumulator.toResponse(latestRun))
                        .toList();

        long totalCount = feedbackList.size();
        return new AiFeedbackStatisticsResponse(
                totalCount,
                helpfulCount,
                unhelpfulCount,
                ratio(helpfulCount, totalCount),
                convertedCaseCount,
                ratio(convertedCaseCount, unhelpfulCount),
                reasonCounts,
                commonFailureQuestions);
    }

    private void validateId(String value, String message) {
        if (value == null || !CLIENT_ID_PATTERN.matcher(value).matches()) {
            throw new BusinessException(400, message);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeReason(String reason) {
        return reason != null && FEEDBACK_REASONS.contains(reason) ? reason : "other";
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "（未记录问题）";
        }
        return question.trim().replaceAll("\\s+", " ");
    }

    private double ratio(long value, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(value * 10_000D / total) / 10_000D;
    }

    /**
     * 最近一次回归报告：跑的时间，以及每个用例的结果。
     */
    private record LatestRun(LocalDateTime ranAt, Map<Long, CaseEvalResult> resultsByCaseId) {

        private static LatestRun empty() {
            return new LatestRun(null, Map.of());
        }
    }

    private record CaseEvalResult(boolean passed, String failureReason) {
    }

    /**
     * 取该用户最近一次回归报告，解析出逐用例结果。
     *
     * 报告以 JSON 存在 ai_rag_eval_run.results_json。解析失败不应该让反馈统计整体失败，
     * 所以这里退化成「没有可关联的报告」，各高频问题的 evalStatus 会显示为 not_run。
     */
    private LatestRun loadLatestRun(Long userId) {
        AiRagEvalRun run = runMapper.selectOne(new LambdaQueryWrapper<AiRagEvalRun>()
                .eq(AiRagEvalRun::getUserId, userId)
                .orderByDesc(AiRagEvalRun::getId)
                .last("LIMIT 1"));
        if (run == null || run.getResultsJson() == null || run.getResultsJson().isBlank()) {
            return LatestRun.empty();
        }

        Map<Long, CaseEvalResult> resultsByCaseId = new HashMap<>();
        try {
            for (RagEvalResultItem item : objectMapper.readValue(run.getResultsJson(), RESULT_LIST_TYPE)) {
                if (item.caseId() != null) {
                    resultsByCaseId.put(
                            item.caseId(),
                            new CaseEvalResult(item.passed(), item.failureReason()));
                }
            }
        }
        catch (JsonProcessingException exception) {
            log.warn("event=ai.feedback.eval_status.unreadable userId={} runId={}",
                    userId, run.getId(), exception);
            return LatestRun.empty();
        }
        return new LatestRun(run.getCreateTime(), resultsByCaseId);
    }

    private static final class QuestionAccumulator {

        private final String question;
        private final Map<String, Long> reasonCounts = new LinkedHashMap<>();
        private final Set<Long> convertedCaseIds = new LinkedHashSet<>();
        private long count;
        private LocalDateTime lastFeedbackAt;

        private QuestionAccumulator(String question) {
            this.question = question;
        }

        private void add(String reason, Long evalCaseId, LocalDateTime updatedAt) {
            count += 1;
            if (evalCaseId != null) {
                convertedCaseIds.add(evalCaseId);
            }
            reasonCounts.merge(reason, 1L, Long::sum);
            if (updatedAt != null && (lastFeedbackAt == null || updatedAt.isAfter(lastFeedbackAt))) {
                lastFeedbackAt = updatedAt;
            }
        }

        private String question() {
            return question;
        }

        private long count() {
            return count;
        }

        private LocalDateTime lastFeedbackAt() {
            return lastFeedbackAt;
        }

        private AiFeedbackStatisticsResponse.CommonFailureQuestion toResponse(LatestRun latestRun) {
            List<AiFeedbackStatisticsResponse.ReasonCount> reasons = new ArrayList<>();
            reasonCounts.forEach((reason, reasonCount) -> reasons.add(
                    new AiFeedbackStatisticsResponse.ReasonCount(
                            reason,
                            reasonCount,
                            count == 0 ? 0 : (double) reasonCount / count)));
            reasons.sort(Comparator.comparingLong(AiFeedbackStatisticsResponse.ReasonCount::count)
                    .reversed());

            // 只统计真正出现在最近一次报告里的用例：被停用或从未跑过的用例
            // 不该被算成「通过」。一个都没出现过就是 not_run。
            List<CaseEvalResult> matched = convertedCaseIds.stream()
                    .map(latestRun.resultsByCaseId()::get)
                    .filter(Objects::nonNull)
                    .toList();

            String evalStatus;
            String evalFailureReason = null;
            if (matched.isEmpty()) {
                evalStatus = EVAL_STATUS_NOT_RUN;
            }
            else {
                CaseEvalResult failed = matched.stream()
                        .filter(result -> !result.passed())
                        .findFirst()
                        .orElse(null);
                if (failed == null) {
                    evalStatus = EVAL_STATUS_PASSED;
                }
                else {
                    evalStatus = EVAL_STATUS_FAILED;
                    evalFailureReason = failed.failureReason();
                }
            }

            return new AiFeedbackStatisticsResponse.CommonFailureQuestion(
                    question,
                    count,
                    convertedCaseIds.size(),
                    lastFeedbackAt,
                    reasons,
                    evalStatus,
                    matched.isEmpty() ? null : latestRun.ranAt(),
                    evalFailureReason);
        }
    }

    public static AiAnswerFeedbackResponse toResponse(AiAnswerFeedback feedback) {
        if (feedback == null) {
            return null;
        }
        return new AiAnswerFeedbackResponse(
                feedback.getRating(),
                feedback.getReason(),
                feedback.getComment(),
                feedback.getEvalCaseId(),
                feedback.getUpdateTime());
    }
}
