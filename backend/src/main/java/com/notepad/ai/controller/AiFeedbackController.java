package com.notepad.ai.controller;

import com.notepad.ai.dto.AiFeedbackStatisticsResponse;
import com.notepad.ai.service.AiAnswerFeedbackService;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/feedback")
@RequiredArgsConstructor
public class AiFeedbackController {

    private final AiAnswerFeedbackService feedbackService;

    @GetMapping("/statistics")
    public Result<AiFeedbackStatisticsResponse> statistics() {
        return Result.ok(feedbackService.statistics(UserContext.getUserId()));
    }
}
