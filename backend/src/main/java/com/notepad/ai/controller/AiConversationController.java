package com.notepad.ai.controller;

import com.notepad.ai.dto.AiConversationDetailResponse;
import com.notepad.ai.dto.AiAnswerFeedbackRequest;
import com.notepad.ai.dto.AiAnswerFeedbackResponse;
import com.notepad.ai.dto.AiConversationSaveRequest;
import com.notepad.ai.dto.AiConversationSummaryResponse;
import com.notepad.ai.service.AiConversationService;
import com.notepad.ai.service.AiAnswerFeedbackService;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiConversationService conversationService;
    private final AiAnswerFeedbackService feedbackService;

    @GetMapping
    public Result<List<AiConversationSummaryResponse>> list() {
        return Result.ok(conversationService.list(UserContext.getUserId()));
    }

    @GetMapping("/{clientId}")
    public Result<AiConversationDetailResponse> detail(@PathVariable String clientId) {
        return Result.ok(conversationService.detail(UserContext.getUserId(), clientId));
    }

    @PutMapping("/{clientId}")
    public Result<AiConversationDetailResponse> save(
            @PathVariable String clientId,
            @Valid @RequestBody AiConversationSaveRequest request) {
        return Result.ok(conversationService.save(UserContext.getUserId(), clientId, request));
    }

    @DeleteMapping("/{clientId}")
    public Result<Void> delete(@PathVariable String clientId) {
        conversationService.delete(UserContext.getUserId(), clientId);
        return Result.ok();
    }

    @PutMapping("/{clientId}/messages/{messageClientId}/feedback")
    public Result<AiAnswerFeedbackResponse> saveFeedback(
            @PathVariable String clientId,
            @PathVariable String messageClientId,
            @Valid @RequestBody AiAnswerFeedbackRequest request) {
        return Result.ok(feedbackService.save(
                UserContext.getUserId(), clientId, messageClientId, request));
    }

    @DeleteMapping("/{clientId}/messages/{messageClientId}/feedback")
    public Result<Void> deleteFeedback(
            @PathVariable String clientId,
            @PathVariable String messageClientId) {
        feedbackService.delete(UserContext.getUserId(), clientId, messageClientId);
        return Result.ok();
    }
}
