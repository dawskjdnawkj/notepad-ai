package com.notepad.service;

import com.notepad.dto.NotebookCreateRequest;
import com.notepad.dto.NotebookRenameRequest;
import com.notepad.common.PageResult;
import com.notepad.vo.NotebookNoteVO;
import com.notepad.vo.NotebookVO;

import java.util.List;

public interface NotebookService {

    List<NotebookVO> list(Long userId);

    PageResult<NotebookNoteVO> listNotes(Long userId, Long notebookId, int page, int pageSize);

    NotebookVO create(Long userId, NotebookCreateRequest request);

    NotebookVO rename(Long userId, Long notebookId, NotebookRenameRequest request);

    void delete(Long userId, Long notebookId);
}
