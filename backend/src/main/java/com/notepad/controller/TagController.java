package com.notepad.controller;

import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.dto.TagCreateRequest;
import com.notepad.service.TagService;
import com.notepad.vo.TagVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(tagService.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<TagVO> create(@Valid @RequestBody TagCreateRequest request) {
        return Result.ok(tagService.create(UserContext.getUserId(), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(UserContext.getUserId(), id);
        return Result.ok();
    }
}
