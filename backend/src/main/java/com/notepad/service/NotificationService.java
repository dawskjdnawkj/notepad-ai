package com.notepad.service;

import com.notepad.common.PageResult;
import com.notepad.vo.NotificationVO;

public interface NotificationService {

    PageResult<NotificationVO> list(Long userId, int page, int pageSize);

    long unreadCount(Long userId);

    void markRead(Long userId, Long id);

    int markAllRead(Long userId);
}
