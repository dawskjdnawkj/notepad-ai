package com.notepad.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.entity.Note;
import com.notepad.mapper.NoteMapper;
import com.notepad.entity.Notification;
import com.notepad.mapper.NotificationMapper;
import com.notepad.entity.Reminder;
import com.notepad.mapper.ReminderMapper;
import com.notepad.entity.User;
import com.notepad.mapper.UserMapper;
import com.notepad.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务：每分钟扫描到期提醒，生成站内通知
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTask {

    private final ReminderMapper reminderMapper;
    private final NoteMapper noteMapper;
    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final MailService mailService;

    @Scheduled(fixedDelay = 60_000)
    @Transactional(rollbackFor = Exception.class)
    public void scanDueReminders() {
        List<Reminder> due = reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getStatus, 0)
                .le(Reminder::getRemindAt, LocalDateTime.now())
                .last("LIMIT 100"));
        if (due.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Reminder reminder : due) {
            Note note = noteMapper.selectById(reminder.getNoteId());
            String title = note != null && note.getTitle() != null && !note.getTitle().isBlank()
                    ? "提醒：" + note.getTitle()
                    : "提醒";
            reminder.setStatus(1);
            reminder.setNotifiedAt(now);
            reminderMapper.updateById(reminder);

            Notification notification = new Notification();
            notification.setUserId(reminder.getUserId());
            notification.setType(1);
            notification.setTitle(title);
            notification.setContent("该查看你的笔记了");
            notification.setNoteId(reminder.getNoteId());
            notification.setIsRead(0);
            notificationMapper.insert(notification);

            sendReminderEmail(reminder, note);
        }
        log.info("提醒扫描完成，本次处理 {} 条", due.size());
    }

    /**
     * 给注册邮箱发送提醒邮件。邮箱缺失或发送失败时仅记录日志，不影响站内通知与提醒状态落库。
     */
    private void sendReminderEmail(Reminder reminder, Note note) {
        User user = userMapper.selectById(reminder.getUserId());
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String noteTitle = note != null && note.getTitle() != null && !note.getTitle().isBlank()
                ? note.getTitle()
                : "无标题笔记";
        try {
            mailService.sendReminder(user.getEmail(), noteTitle);
        } catch (Exception e) {
            log.warn("提醒邮件发送失败 noteId={}, email={}: {}", reminder.getNoteId(), user.getEmail(), e.getMessage());
        }
    }
}
