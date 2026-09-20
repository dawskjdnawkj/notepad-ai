package com.notepad.task;

import com.notepad.service.NoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时回填已有笔记的正文纯文本列（content_text），保证老笔记也能被搜索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentTextBackfillRunner implements ApplicationRunner {

    private final NoteService noteService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = noteService.backfillContentText();
            if (count > 0) {
                log.info("回填笔记纯文本 content_text {} 条", count);
            }
        } catch (Exception e) {
            log.error("回填 content_text 失败", e);
        }
    }
}
