package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notepad.ai.event.NoteVectorSyncEvent;
import com.notepad.common.BusinessException;
import com.notepad.common.PageResult;
import com.notepad.dto.NotebookCreateRequest;
import com.notepad.dto.NotebookRenameRequest;
import com.notepad.entity.Notebook;
import com.notepad.mapper.NotebookMapper;
import com.notepad.service.NotebookService;
import com.notepad.vo.NotebookNoteVO;
import com.notepad.vo.NotebookVO;
import com.notepad.entity.Note;
import com.notepad.mapper.NoteMapper;
import com.notepad.mapper.ReminderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotebookServiceImpl implements NotebookService {

    private final NotebookMapper notebookMapper;
    private final NoteMapper noteMapper;
    private final ReminderMapper reminderMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<NotebookVO> list(Long userId) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .orderByDesc(Notebook::getCreateTime));
        Map<Long, Long> counts = noteCounts(userId);
        return notebooks.stream()
                .map(n -> new NotebookVO(n.getId(), n.getName(),
                        n.getIsDefault() != null && n.getIsDefault() == 1,
                        counts.getOrDefault(n.getId(), 0L), n.getCreateTime()))
                .toList();
    }

    @Override
    public PageResult<NotebookNoteVO> listNotes(Long userId, Long notebookId, int page, int pageSize) {
        requireOwned(userId, notebookId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        IPage<Note> result = noteMapper.selectNotePage(new Page<>(safePage, safeSize),
                userId, notebookId, null, null, null, null);
        List<NotebookNoteVO> records = result.getRecords().stream()
                .map(n -> new NotebookNoteVO(n.getId(), n.getTitle()))
                .toList();
        return new PageResult<>(result.getTotal(), safePage, safeSize, records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotebookVO create(Long userId, NotebookCreateRequest request) {
        String name = request.getName().trim();
        checkUnique(userId, name, null);
        Notebook notebook = new Notebook();
        notebook.setUserId(userId);
        notebook.setName(name);
        notebook.setIsDefault(0);
        notebookMapper.insert(notebook);
        return new NotebookVO(notebook.getId(), name, false, 0L, notebook.getCreateTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotebookVO rename(Long userId, Long notebookId, NotebookRenameRequest request) {
        Notebook notebook = requireOwned(userId, notebookId);
        if (notebook.getIsDefault() != null && notebook.getIsDefault() == 1) {
            throw new BusinessException(400, "默认笔记本不可重命名");
        }
        String name = request.getName().trim();
        checkUnique(userId, name, notebookId);
        notebook.setName(name);
        notebookMapper.updateById(notebook);
        return new NotebookVO(notebook.getId(), name, false, 0L, notebook.getCreateTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long notebookId) {
        Notebook notebook = requireOwned(userId, notebookId);
        if (notebook.getIsDefault() != null && notebook.getIsDefault() == 1) {
            throw new BusinessException(400, "默认笔记本不可删除");
        }
        List<Long> noteIds = noteMapper.selectIdsByNotebook(userId, notebookId);
        if (!noteIds.isEmpty()) {
            reminderMapper.cancelByNoteIds(userId, noteIds);
            noteMapper.toTrashByNotebook(userId, notebookId);
            noteIds.forEach(noteId ->
                    eventPublisher.publishEvent(NoteVectorSyncEvent.delete(userId, noteId)));
        }
        notebookMapper.deleteById(notebookId);
    }

    private Notebook requireOwned(Long userId, Long notebookId) {
        Notebook notebook = notebookMapper.selectById(notebookId);
        if (notebook == null || !notebook.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记本不存在");
        }
        return notebook;
    }

    private void checkUnique(Long userId, String name, Long excludeId) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "笔记本名称不能为空");
        }
        Long count = notebookMapper.selectCount(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getName, name)
                .ne(excludeId != null, Notebook::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(409, "笔记本名称已存在");
        }
    }

    private Map<Long, Long> noteCounts(Long userId) {
        List<Map<String, Object>> rows = noteMapper.selectMaps(new QueryWrapper<Note>()
                .select("notebook_id", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .eq("deleted", 0)
                .groupBy("notebook_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("notebook_id") instanceof Number id && row.get("cnt") instanceof Number cnt) {
                counts.put(id.longValue(), cnt.longValue());
            }
        }
        return counts;
    }
}
