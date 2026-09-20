package com.notepad.ai.dto;

import java.time.LocalDateTime;

/**
 * 一条可回退的原文快照。
 */
public record AiNoteRevisionResponse(
        Long id,
        String capability,
        LocalDateTime createTime,
        boolean restored,
        LocalDateTime restoreTime
) {
}
