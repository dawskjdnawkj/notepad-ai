package com.notepad.vo;

import com.notepad.entity.Notification;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class NotificationVO {

    private Long id;
    private Integer type;
    private String title;
    private String content;
    private Long noteId;
    private Integer isRead;
    private LocalDateTime createTime;

    public static NotificationVO from(Notification notification) {
        return new NotificationVO(notification.getId(), notification.getType(),
                notification.getTitle(), notification.getContent(), notification.getNoteId(),
                notification.getIsRead(), notification.getCreateTime());
    }
}
