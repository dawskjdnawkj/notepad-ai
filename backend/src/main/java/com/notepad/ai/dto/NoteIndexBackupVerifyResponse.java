package com.notepad.ai.dto;

/**
 * 备份文件的校验结果；valid 为 false 时 issueMessage 说明首个失败原因。
 */
public record NoteIndexBackupVerifyResponse(
        String fileName,
        boolean valid,
        int entryCount,
        int userCount,
        String issueMessage
) {
}
