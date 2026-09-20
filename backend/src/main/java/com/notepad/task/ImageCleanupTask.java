package com.notepad.task;

import com.notepad.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 图片清理定时任务：每日凌晨清理孤儿图片
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupTask {

    private final ImageService imageService;

    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanupOrphanImages() {
        try {
            int count = imageService.cleanupOrphanImages();
            int purged = imageService.purgeDeletedImages();
            if (count > 0 || purged > 0) {
                log.info("图片清理完成：孤儿 {} 张，软删记录 {} 条", count, purged);
            }
        } catch (Exception e) {
            log.error("图片清理任务异常", e);
        }
    }
}
