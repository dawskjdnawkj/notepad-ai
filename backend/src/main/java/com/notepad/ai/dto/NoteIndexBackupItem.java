package com.notepad.ai.dto;

import java.time.Instant;

/**
 * 一份索引备份文件的概要信息。
 */
public record NoteIndexBackupItem(
        String fileName,
        long fileBytes,
        Instant createdAt,
        String reason
) {
}
