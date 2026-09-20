package com.notepad.ai.dto;

/**
 * 从备份恢复索引后的结果，附带刷新后的健康状态。
 */
public record NoteIndexRestoreResponse(
        String fileName,
        int restoredEntryCount,
        long storeFileBytes,
        String snapshotFileName,
        NoteIndexStatusResponse status
) {
}
