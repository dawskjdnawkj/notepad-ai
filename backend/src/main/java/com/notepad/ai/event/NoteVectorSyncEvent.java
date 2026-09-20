package com.notepad.ai.event;

/**
 * 笔记事务提交后需要执行的向量同步操作。
 */
public record NoteVectorSyncEvent(
        Long userId,
        Long noteId,
        Operation operation
) {

    public enum Operation {
        UPSERT,
        DELETE
    }

    public static NoteVectorSyncEvent upsert(Long userId, Long noteId) {
        return new NoteVectorSyncEvent(userId, noteId, Operation.UPSERT);
    }

    public static NoteVectorSyncEvent delete(Long userId, Long noteId) {
        return new NoteVectorSyncEvent(userId, noteId, Operation.DELETE);
    }
}
