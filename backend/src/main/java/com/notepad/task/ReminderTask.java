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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务：每分钟扫描到期提醒，生成站内通知
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTask {

    /** 通知标题列宽（notification.title 为 VARCHAR(100)） */
    private static final int NOTIFICATION_TITLE_MAX = 100;

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
        // 邮件是不可回滚的外部副作用，攒到事务提交后再发：
        // 否则一旦事务回滚（例如某条插入失败），已经发出去的信会在下一分钟被重复发送
        List<Runnable> pendingMails = new ArrayList<>();
        for (Reminder reminder : due) {
            Note note = noteMapper.selectById(reminder.getNoteId());
            reminder.setStatus(1);
            reminder.setNotifiedAt(now);
            reminderMapper.updateById(reminder);

            Notification notification = new Notification();
            notification.setUserId(reminder.getUserId());
            notification.setType(1);
            notification.setTitle(buildNotificationTitle(note));
            notification.setContent("该查看你的笔记了");
            notification.setNoteId(reminder.getNoteId());
            notification.setIsRead(0);
            notificationMapper.insert(notification);

            // note 可能为 null（笔记已被删除）：保持原有行为，仍发信，由 sendReminderEmail 兜底
            pendingMails.add(() -> sendReminderEmail(reminder, note));
        }
        if (!pendingMails.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pendingMails.forEach(Runnable::run);
                }
            });
        }
        log.info("提醒扫描完成，本次处理 {} 条", due.size());
    }

    /**
     * 通知标题列是 VARCHAR(100)，而笔记标题最长 200（见 NoteRenameRequest 的 @Size(max=200)）。
     * 直接拼接最长 203 字符，在开了 STRICT_TRANS_TABLES 的 MySQL 上会让 insert 直接报错，
     * 而整个扫描是同一个事务 —— 一条超长标题就能让整批提醒回滚、且每分钟重复触发。
     */
    private String buildNotificationTitle(Note note) {
        if (note == null || note.getTitle() == null || note.getTitle().isBlank()) {
            return "提醒";
        }
        String title = "提醒：" + note.getTitle();
        return title.length() > NOTIFICATION_TITLE_MAX
                ? title.substring(0, NOTIFICATION_TITLE_MAX)
                : title;
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
