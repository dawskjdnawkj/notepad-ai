package com.notepad.service;

import com.notepad.dto.ReminderRequest;
import com.notepad.vo.ReminderVO;

public interface ReminderService {

    ReminderVO set(Long userId, Long noteId, ReminderRequest request);

    void cancel(Long userId, Long noteId);
}
