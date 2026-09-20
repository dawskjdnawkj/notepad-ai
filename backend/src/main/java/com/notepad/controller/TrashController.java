package com.notepad.controller;

import com.notepad.common.PageResult;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.service.NoteService;
import com.notepad.vo.NoteListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trash/notes")
@RequiredArgsConstructor
public class TrashController {

    private final NoteService noteService;

    @GetMapping
    public Result<PageResult<NoteListItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(noteService.trashList(UserContext.getUserId(), page, pageSize));
    }

    @PutMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        noteService.restore(UserContext.getUserId(), id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteForever(@PathVariable Long id) {
        noteService.deleteForever(UserContext.getUserId(), id);
        return Result.ok();
    }

    @DeleteMapping
    public Result<Map<String, Integer>> clear() {
        return Result.ok(Map.of("count", noteService.clearTrash(UserContext.getUserId())));
    }
}
