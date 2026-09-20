package com.notepad.controller;

import com.notepad.common.Result;
import com.notepad.common.PageResult;
import com.notepad.common.UserContext;
import com.notepad.dto.NotebookCreateRequest;
import com.notepad.dto.NotebookRenameRequest;
import com.notepad.service.NotebookService;
import com.notepad.vo.NotebookNoteVO;
import com.notepad.vo.NotebookVO;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/notebooks")
@RequiredArgsConstructor
public class NotebookController {

    private final NotebookService notebookService;

    @GetMapping
    public Result<List<NotebookVO>> list() {
        return Result.ok(notebookService.list(UserContext.getUserId()));
    }

    @GetMapping("/{id}/notes")
    public Result<PageResult<NotebookNoteVO>> listNotes(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return Result.ok(notebookService.listNotes(UserContext.getUserId(), id, page, pageSize));
    }

    @PostMapping
    public Result<NotebookVO> create(@Valid @RequestBody NotebookCreateRequest request) {
        return Result.ok(notebookService.create(UserContext.getUserId(), request));
    }

    @PutMapping("/{id}")
    public Result<NotebookVO> rename(@PathVariable Long id, @Valid @RequestBody NotebookRenameRequest request) {
        return Result.ok(notebookService.rename(UserContext.getUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        notebookService.delete(UserContext.getUserId(), id);
        return Result.ok();
    }
}
