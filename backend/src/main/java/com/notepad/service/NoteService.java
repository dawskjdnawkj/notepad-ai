package com.notepad.service;

import com.notepad.common.PageResult;
import com.notepad.vo.CalendarMarksVO;
import com.notepad.dto.NoteCreateRequest;
import com.notepad.dto.NoteUpdateRequest;
import com.notepad.vo.NoteDetailVO;
import com.notepad.vo.NoteListItemVO;

public interface NoteService {

    PageResult<NoteListItemVO> list(Long userId, int page, int pageSize,
                                    Long notebookId, Long tagId, String keyword, String createdDate);

    NoteDetailVO detail(Long userId, Long noteId);

    NoteDetailVO create(Long userId, NoteCreateRequest request);

    NoteDetailVO update(Long userId, Long noteId, NoteUpdateRequest request);

    void move(Long userId, Long noteId, Long notebookId);

    void pin(Long userId, Long noteId, boolean pinned);

    void rename(Long userId, Long noteId, String title);

    void trash(Long userId, Long noteId);

    PageResult<NoteListItemVO> trashList(Long userId, int page, int pageSize);

    void restore(Long userId, Long noteId);

    void deleteForever(Long userId, Long noteId);

    int clearTrash(Long userId);

    CalendarMarksVO calendarMarks(Long userId, String month);

    int purgeExpiredTrash();

    int backfillContentText();
}
