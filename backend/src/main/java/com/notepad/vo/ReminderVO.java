package com.notepad.vo;

import com.notepad.entity.Reminder;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ReminderVO {

    private Long id;
    private Long noteId;
    private LocalDateTime remindAt;
    private Integer status;

    public static ReminderVO from(Reminder reminder) {
        return new ReminderVO(reminder.getId(), reminder.getNoteId(),
                reminder.getRemindAt(), reminder.getStatus());
    }
}
