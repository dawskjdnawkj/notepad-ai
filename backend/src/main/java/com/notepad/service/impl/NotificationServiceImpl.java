package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notepad.common.BusinessException;
import com.notepad.common.PageResult;
import com.notepad.entity.Notification;
import com.notepad.mapper.NotificationMapper;
import com.notepad.service.NotificationService;
import com.notepad.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    public PageResult<NotificationVO> list(Long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        IPage<Notification> result = notificationMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .orderByAsc(Notification::getIsRead)
                        .orderByDesc(Notification::getCreateTime));
        List<NotificationVO> records = result.getRecords().stream().map(NotificationVO::from).toList();
        return new PageResult<>(result.getTotal(), safePage, safeSize, records);
    }

    @Override
    public long unreadCount(Long userId) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        return count == null ? 0 : count;
    }

    @Override
    public void markRead(Long userId, Long id) {
        int updated = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(404, "通知不存在");
        }
    }

    @Override
    public int markAllRead(Long userId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadTime, LocalDateTime.now()));
    }
}
