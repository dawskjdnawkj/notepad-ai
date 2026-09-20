package com.notepad.service.impl;

import com.notepad.common.BusinessException;
import com.notepad.entity.Note;
import com.notepad.mapper.NoteMapper;
import com.notepad.dto.ReminderRequest;
import com.notepad.entity.Reminder;
import com.notepad.mapper.ReminderMapper;
import com.notepad.service.ReminderService;
import com.notepad.vo.ReminderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReminderServiceImpl implements ReminderService {

    private final ReminderMapper reminderMapper;
    private final NoteMapper noteMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO set(Long userId, Long noteId, ReminderRequest request) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        int value = request.getValue();
        LocalDateTime remindAt;
        if ("HOURS".equals(request.getUnit())) {
            remindAt = LocalDateTime.now().plusHours(value);
        } else if ("DAYS".equals(request.getUnit())) {
            remindAt = LocalDateTime.now().plusDays(value);
        } else if ("MINUTES".equals(request.getUnit())) {
            remindAt = LocalDateTime.now().plusMinutes(value);
        } else {
            throw new BusinessException(400, "unit 只能为 HOURS、DAYS 或 MINUTES");
        }
        // 重复设置自动覆盖旧的待提醒
        reminderMapper.cancelPending(noteId, userId);
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setNoteId(noteId);
        reminder.setRemindAt(remindAt);
        reminder.setStatus(0);
        reminderMapper.insert(reminder);
        return ReminderVO.from(reminder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        reminderMapper.cancelPending(noteId, userId);
    }
}
