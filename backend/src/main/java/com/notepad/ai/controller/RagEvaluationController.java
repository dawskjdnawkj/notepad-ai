package com.notepad.ai.controller;

import com.notepad.ai.dto.RagEvalCaseRequest;
import com.notepad.ai.dto.RagEvalCaseResponse;
import com.notepad.ai.dto.RagEvalRunResponse;
import com.notepad.ai.dto.RagEvalRunSummaryResponse;
import com.notepad.ai.service.RagEvaluationService;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/evaluations")
@RequiredArgsConstructor
public class RagEvaluationController {

    private final RagEvaluationService evaluationService;

    @GetMapping("/cases")
    public Result<List<RagEvalCaseResponse>> listCases() {
        return Result.ok(evaluationService.listCases(UserContext.getUserId()));
    }

    @PostMapping("/cases")
    public Result<RagEvalCaseResponse> createCase(
            @Valid @RequestBody RagEvalCaseRequest request) {
        return Result.ok(evaluationService.createCase(UserContext.getUserId(), request));
    }

    @PostMapping("/cases/from-feedback/{conversationClientId}/{messageClientId}")
    public Result<RagEvalCaseResponse> createCaseFromFeedback(
            @PathVariable String conversationClientId,
            @PathVariable String messageClientId,
            @Valid @RequestBody RagEvalCaseRequest request) {
        return Result.ok(evaluationService.createCaseFromFeedback(
                UserContext.getUserId(), conversationClientId, messageClientId, request));
    }

    @PutMapping("/cases/{caseId}")
    public Result<RagEvalCaseResponse> updateCase(
            @PathVariable Long caseId,
            @Valid @RequestBody RagEvalCaseRequest request) {
        return Result.ok(evaluationService.updateCase(UserContext.getUserId(), caseId, request));
    }

    @DeleteMapping("/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable Long caseId) {
        evaluationService.deleteCase(UserContext.getUserId(), caseId);
        return Result.ok();
    }

    @GetMapping("/runs")
    public Result<List<RagEvalRunSummaryResponse>> listRuns() {
        return Result.ok(evaluationService.listRuns(UserContext.getUserId()));
    }

    @PostMapping("/runs")
    public Result<RagEvalRunResponse> run() {
        return Result.ok(evaluationService.runEnabledCases(UserContext.getUserId()));
    }

    @GetMapping("/runs/{runId}")
    public Result<RagEvalRunResponse> runDetail(@PathVariable Long runId) {
        return Result.ok(evaluationService.runDetail(UserContext.getUserId(), runId));
    }
}
