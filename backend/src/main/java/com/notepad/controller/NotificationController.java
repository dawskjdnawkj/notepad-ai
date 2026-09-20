package com.notepad.controller;

import com.notepad.common.PageResult;
import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.service.NotificationService;
import com.notepad.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Result<PageResult<NotificationVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(notificationService.list(UserContext.getUserId(), page, pageSize));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.ok(Map.of("count", notificationService.unreadCount(UserContext.getUserId())));
    }

    @PutMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(UserContext.getUserId(), id);
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Map<String, Integer>> readAll() {
        return Result.ok(Map.of("count", notificationService.markAllRead(UserContext.getUserId())));
    }
}
