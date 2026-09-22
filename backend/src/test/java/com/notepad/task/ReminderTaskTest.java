package com.notepad.task;

import com.notepad.entity.Note;
import com.notepad.entity.Notification;
import com.notepad.entity.Reminder;
import com.notepad.mapper.NoteMapper;
import com.notepad.mapper.NotificationMapper;
import com.notepad.mapper.ReminderMapper;
import com.notepad.mapper.UserMapper;
import com.notepad.service.MailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提醒扫描。
 * <p>
 * 这里守的是一个真实事故：通知标题列是 VARCHAR(100)，而笔记标题最长 200，
 * 拼上「提醒：」前缀后最长 203。MySQL 开着 STRICT_TRANS_TABLES 会直接抛错，
 * 而整个扫描共用一个事务 —— 一条超长标题就能让整批提醒回滚、且每分钟重复触发。
 */
@ExtendWith(MockitoExtension.class)
class ReminderTaskTest {

    /** notification.title 的列宽 */
    private static final int TITLE_MAX = 100;

    @Mock
    private ReminderMapper reminderMapper;
    @Mock
    private NoteMapper noteMapper;
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MailService mailService;

    @InjectMocks
    private ReminderTask task;

    private void givenDueReminderForNoteTitle(String noteTitle) {
        Reminder reminder = new Reminder();
        reminder.setId(1L);
        reminder.setUserId(7L);
        reminder.setNoteId(99L);
        reminder.setStatus(0);
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));

        Note note = new Note();
        note.setId(99L);
        note.setTitle(noteTitle);

        when(reminderMapper.selectList(any())).thenReturn(List.of(reminder));
        when(noteMapper.selectById(any())).thenReturn(note);
    }

    private String capturedNotificationTitle() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        return captor.getValue().getTitle();
    }

    @Test
    @DisplayName("超长标题要截断到列宽，不能让它把整批提醒拖回滚")
    void truncatesOverlongTitle() {
        givenDueReminderForNoteTitle("长".repeat(200));

        task.scanDueReminders();

        assertThat(capturedNotificationTitle())
                .as("标题必须放得进 VARCHAR(100)，否则 insert 直接报错")
                .hasSize(TITLE_MAX);
    }

    @Test
    @DisplayName("刚好卡在边界上的标题不应被改动")
    void keepsTitleThatExactlyFits() {
        // 「提醒：」占 3 个字符，所以笔记标题 97 个字符时正好 100
        givenDueReminderForNoteTitle("标".repeat(97));

        task.scanDueReminders();

        assertThat(capturedNotificationTitle()).hasSize(TITLE_MAX);
    }

    @Test
    @DisplayName("正常长度的标题保持原样，不要无谓地截断用户看得见的内容")
    void keepsShortTitleIntact() {
        givenDueReminderForNoteTitle("会议记录");

        task.scanDueReminders();

        assertThat(capturedNotificationTitle()).isEqualTo("提醒：会议记录");
    }

    @Test
    @DisplayName("笔记标题为空时退化成「提醒」")
    void fallsBackWhenNoteTitleIsBlank() {
        givenDueReminderForNoteTitle("   ");

        task.scanDueReminders();

        assertThat(capturedNotificationTitle()).isEqualTo("提醒");
    }

    @Test
    @DisplayName("笔记已被删除（查不到）时同样退化成「提醒」，不能抛 NPE")
    void fallsBackWhenNoteIsGone() {
        givenDueReminderForNoteTitle(null);
        when(noteMapper.selectById(any())).thenReturn(null);

        task.scanDueReminders();

        assertThat(capturedNotificationTitle()).isEqualTo("提醒");
    }

    @Test
    @DisplayName("没有到期提醒时不该产生任何写入")
    void doesNothingWhenNothingIsDue() {
        when(reminderMapper.selectList(any())).thenReturn(List.of());

        task.scanDueReminders();

        verify(notificationMapper, org.mockito.Mockito.never()).insert(any(Notification.class));
    }
}
