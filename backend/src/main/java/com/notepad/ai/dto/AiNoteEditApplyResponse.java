package com.notepad.ai.dto;

import com.notepad.vo.NoteDetailVO;

/**
 * 写回成功后的结果，带上快照 ID 便于前端提示「可以恢复原文」。
 */
public record AiNoteEditApplyResponse(
        Long revisionId,
        NoteDetailVO note
) {
}
