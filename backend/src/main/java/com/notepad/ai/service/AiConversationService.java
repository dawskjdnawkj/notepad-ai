package com.notepad.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notepad.ai.dto.AiConversationDetailResponse;
import com.notepad.ai.dto.AiConversationMessagePayload;
import com.notepad.ai.dto.AiConversationSaveRequest;
import com.notepad.ai.dto.AiConversationSource;
import com.notepad.ai.dto.AiConversationSummaryResponse;
import com.notepad.common.BusinessException;
import com.notepad.entity.AiAnswerFeedback;
import com.notepad.entity.AiConversation;
import com.notepad.entity.AiConversationMessageEntity;
import com.notepad.mapper.AiAnswerFeedbackMapper;
import com.notepad.mapper.AiConversationMapper;
import com.notepad.mapper.AiConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationService {

    private static final int MAX_CONVERSATIONS = 30;
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final TypeReference<List<AiConversationSource>> SOURCE_LIST_TYPE =
            new TypeReference<>() { };

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiAnswerFeedbackMapper feedbackMapper;
    private final ObjectMapper objectMapper;

    public List<AiConversationSummaryResponse> list(Long userId) {
        List<AiConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdateTime)
                        .last("LIMIT " + MAX_CONVERSATIONS));

        return conversations.stream()
                .map(conversation -> new AiConversationSummaryResponse(
                        conversation.getClientId(),
                        conversation.getTitle(),
                        messageCount(conversation.getId(), userId) / 2,
                        conversation.getUpdateTime()))
                .toList();
    }

    public AiConversationDetailResponse detail(Long userId, String clientId) {
        AiConversation conversation = requireOwned(userId, clientId);
        List<AiConversationMessageEntity> entities = messageMapper.selectList(
                        new LambdaQueryWrapper<AiConversationMessageEntity>()
                                .eq(AiConversationMessageEntity::getConversationId, conversation.getId())
                                .eq(AiConversationMessageEntity::getUserId, userId)
                                .orderByAsc(AiConversationMessageEntity::getSequenceNo));
        Map<String, AiAnswerFeedback> feedbackByMessageId = new HashMap<>();
        feedbackMapper.selectList(new LambdaQueryWrapper<AiAnswerFeedback>()
                        .eq(AiAnswerFeedback::getUserId, userId)
                        .eq(AiAnswerFeedback::getConversationClientId, clientId))
                .forEach(feedback -> feedbackByMessageId.put(
                        feedback.getMessageClientId(), feedback));
        List<AiConversationMessagePayload> messages = entities
                .stream()
                .map(entity -> toPayload(
                        entity,
                        feedbackByMessageId.get(entity.getClientMessageId())))
                .toList();

        return new AiConversationDetailResponse(
                conversation.getClientId(),
                conversation.getTitle(),
                conversation.getUpdateTime(),
                messages);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiConversationDetailResponse save(
            Long userId,
            String clientId,
            AiConversationSaveRequest request) {
        validateClientId(clientId);
        validateMessageSequence(request.messages());

        LocalDateTime now = LocalDateTime.now();
        AiConversation conversation = findOwned(userId, clientId);
        if (conversation == null) {
            conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setClientId(clientId);
            conversation.setTitle(request.title().trim());
            conversation.setCreateTime(now);
            conversation.setUpdateTime(now);
            conversationMapper.insert(conversation);
        } else {
            conversation.setTitle(request.title().trim());
            conversation.setUpdateTime(now);
            conversationMapper.updateById(conversation);
            messageMapper.delete(new LambdaQueryWrapper<AiConversationMessageEntity>()
                    .eq(AiConversationMessageEntity::getConversationId, conversation.getId())
                    .eq(AiConversationMessageEntity::getUserId, userId));
        }

        for (int index = 0; index < request.messages().size(); index++) {
            AiConversationMessagePayload payload = request.messages().get(index);
            AiConversationMessageEntity entity = new AiConversationMessageEntity();
            entity.setConversationId(conversation.getId());
            entity.setUserId(userId);
            entity.setClientMessageId(resolveClientMessageId(payload.clientMessageId(), index));
            entity.setSequenceNo(index);
            entity.setRole(payload.role());
            entity.setContent(payload.content());
            entity.setQuestion(payload.question());
            entity.setSourcesJson(writeSources(payload.sources()));
            entity.setScopeType(resolveScopeType(payload));
            entity.setScopeId(payload.scopeId());
            entity.setCreateTime(now);
            messageMapper.insert(entity);
        }

        removeOldestConversations(userId);
        return detail(userId, clientId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String clientId) {
        AiConversation conversation = requireOwned(userId, clientId);
        messageMapper.delete(new LambdaQueryWrapper<AiConversationMessageEntity>()
                .eq(AiConversationMessageEntity::getConversationId, conversation.getId())
                .eq(AiConversationMessageEntity::getUserId, userId));
        feedbackMapper.delete(new LambdaQueryWrapper<AiAnswerFeedback>()
                .eq(AiAnswerFeedback::getUserId, userId)
                .eq(AiAnswerFeedback::getConversationClientId, clientId));
        conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getId, conversation.getId())
                .eq(AiConversation::getUserId, userId));
    }

    private long messageCount(Long conversationId, Long userId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<AiConversationMessageEntity>()
                .eq(AiConversationMessageEntity::getConversationId, conversationId)
                .eq(AiConversationMessageEntity::getUserId, userId));
        return count == null ? 0 : count;
    }

    private AiConversation findOwned(Long userId, String clientId) {
        validateClientId(clientId);
        return conversationMapper.selectOne(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId)
                .eq(AiConversation::getClientId, clientId));
    }

    private AiConversation requireOwned(Long userId, String clientId) {
        AiConversation conversation = findOwned(userId, clientId);
        if (conversation == null) {
            throw new BusinessException(404, "AI 会话不存在");
        }
        return conversation;
    }

    private void validateClientId(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new BusinessException(400, "会话 ID 格式不正确");
        }
    }

    private void validateMessageSequence(List<AiConversationMessagePayload> messages) {
        if (messages.size() % 2 != 0) {
            throw new BusinessException(400, "会话消息必须由完整的问答轮次组成");
        }
        Set<String> messageIds = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            AiConversationMessagePayload message = messages.get(index);
            String expectedRole = index % 2 == 0 ? "user" : "assistant";
            if (!expectedRole.equals(message.role())) {
                throw new BusinessException(400, "会话消息角色顺序不正确");
            }
            List<AiConversationSource> sources = message.sources();
            if (message.role().equals("user") && sources != null && !sources.isEmpty()) {
                throw new BusinessException(400, "用户消息不能包含引用片段");
            }
            String messageId = resolveClientMessageId(message.clientMessageId(), index);
            if (!messageIds.add(messageId)) {
                throw new BusinessException(400, "会话消息 ID 不能重复");
            }
            validateScope(message);
        }
    }

    private void validateScope(AiConversationMessagePayload message) {
        if (message.role().equals("user")) {
            if (message.scopeType() != null || message.scopeId() != null) {
                throw new BusinessException(400, "用户消息不能包含检索范围");
            }
            return;
        }
        String scopeType = message.scopeType() == null ? "all" : message.scopeType();
        if (scopeType.equals("all") && message.scopeId() != null) {
            throw new BusinessException(400, "全部笔记范围不能包含范围 ID");
        }
        if (!scopeType.equals("all") && message.scopeId() == null) {
            throw new BusinessException(400, "当前笔记或笔记本范围必须包含范围 ID");
        }
    }

    private String resolveClientMessageId(String clientMessageId, int sequenceNo) {
        return clientMessageId == null || clientMessageId.isBlank()
                ? "legacy-sequence-" + sequenceNo
                : clientMessageId;
    }

    private String resolveScopeType(AiConversationMessagePayload payload) {
        if (payload.role().equals("user")) {
            return null;
        }
        return payload.scopeType() == null ? "all" : payload.scopeType();
    }

    private String writeSources(List<AiConversationSource> sources) {
        try {
            return objectMapper.writeValueAsString(sources == null ? List.of() : sources);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "引用片段格式不正确");
        }
    }

    private AiConversationMessagePayload toPayload(
            AiConversationMessageEntity entity,
            AiAnswerFeedback feedback) {
        return new AiConversationMessagePayload(
                entity.getRole(),
                entity.getContent(),
                entity.getQuestion(),
                readSources(entity.getSourcesJson()),
                entity.getClientMessageId(),
                entity.getScopeType(),
                entity.getScopeId(),
                AiAnswerFeedbackService.toResponse(feedback));
    }

    private List<AiConversationSource> readSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCE_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            log.warn("AI 会话引用数据无法解析，已返回空引用", exception);
            return List.of();
        }
    }

    private void removeOldestConversations(Long userId) {
        List<AiConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .orderByDesc(AiConversation::getUpdateTime));
        if (conversations.size() <= MAX_CONVERSATIONS) {
            return;
        }

        List<AiConversation> oldest = new ArrayList<>(
                conversations.subList(MAX_CONVERSATIONS, conversations.size()));
        for (AiConversation conversation : oldest) {
            messageMapper.delete(new LambdaQueryWrapper<AiConversationMessageEntity>()
                    .eq(AiConversationMessageEntity::getConversationId, conversation.getId())
                    .eq(AiConversationMessageEntity::getUserId, userId));
            feedbackMapper.delete(new LambdaQueryWrapper<AiAnswerFeedback>()
                    .eq(AiAnswerFeedback::getUserId, userId)
                    .eq(AiAnswerFeedback::getConversationClientId, conversation.getClientId()));
            conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
                    .eq(AiConversation::getId, conversation.getId())
                    .eq(AiConversation::getUserId, userId));
        }
    }
}
