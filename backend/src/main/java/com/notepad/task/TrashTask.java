package com.notepad.task;

import com.notepad.service.NoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：每天凌晨 3 点清理回收站中超过 30 天的笔记
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashTask {

    private final NoteService noteService;

    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeExpiredTrash() {
        int count = noteService.purgeExpiredTrash();
        if (count > 0) {
            log.info("清理回收站过期笔记 {} 条", count);
        }
    }
}
