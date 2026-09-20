package com.notepad.controller;

import com.notepad.common.PageResult;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.dto.NoteCreateRequest;
import com.notepad.dto.NoteMoveRequest;
import com.notepad.dto.NotePinRequest;
import com.notepad.dto.NoteRenameRequest;
import com.notepad.dto.NoteUpdateRequest;
import com.notepad.service.NoteService;
import com.notepad.vo.NoteDetailVO;
import com.notepad.vo.NoteListItemVO;
import com.notepad.dto.ReminderRequest;
import com.notepad.service.ReminderService;
import com.notepad.vo.ReminderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final ReminderService reminderService;

    @GetMapping
    public Result<PageResult<NoteListItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long notebookId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String createdDate) {
        return Result.ok(noteService.list(UserContext.getUserId(), page, pageSize,
                notebookId, tagId, keyword, createdDate));
    }

    @GetMapping("/{id}")
    public Result<NoteDetailVO> detail(@PathVariable Long id) {
        return Result.ok(noteService.detail(UserContext.getUserId(), id));
    }

    @PostMapping
    public Result<NoteDetailVO> create(@Valid @RequestBody NoteCreateRequest request) {
        return Result.ok(noteService.create(UserContext.getUserId(), request));
    }

    @PutMapping("/{id}")
    public Result<NoteDetailVO> update(@PathVariable Long id, @Valid @RequestBody NoteUpdateRequest request) {
        return Result.ok(noteService.update(UserContext.getUserId(), id, request));
    }

    @PutMapping("/{id}/notebook")
    public Result<Void> move(@PathVariable Long id, @Valid @RequestBody NoteMoveRequest request) {
        noteService.move(UserContext.getUserId(), id, request.getNotebookId());
        return Result.ok();
    }

    @PutMapping("/{id}/pin")
    public Result<Void> pin(@PathVariable Long id, @Valid @RequestBody NotePinRequest request) {
        noteService.pin(UserContext.getUserId(), id, request.getPinned());
        return Result.ok();
    }

    @PutMapping("/{id}/title")
    public Result<Void> rename(@PathVariable Long id, @Valid @RequestBody NoteRenameRequest request) {
        noteService.rename(UserContext.getUserId(), id, request.getTitle());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> trash(@PathVariable Long id) {
        noteService.trash(UserContext.getUserId(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/reminder")
    public Result<ReminderVO> setReminder(@PathVariable Long id, @Valid @RequestBody ReminderRequest request) {
        return Result.ok(reminderService.set(UserContext.getUserId(), id, request));
    }

    @DeleteMapping("/{id}/reminder")
    public Result<Void> cancelReminder(@PathVariable Long id) {
        reminderService.cancel(UserContext.getUserId(), id);
        return Result.ok();
    }
}
