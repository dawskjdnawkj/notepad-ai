package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notepad.ai.event.NoteVectorSyncEvent;
import com.notepad.common.BusinessException;
import com.notepad.common.PageResult;
import com.notepad.vo.CalendarMarksVO;
import com.notepad.entity.Notebook;
import com.notepad.mapper.NotebookMapper;
import com.notepad.dto.NoteCreateRequest;
import com.notepad.dto.NoteUpdateRequest;
import com.notepad.entity.Note;
import com.notepad.entity.NoteTag;
import com.notepad.mapper.NoteMapper;
import com.notepad.mapper.NoteTagMapper;
import com.notepad.service.NoteService;
import com.notepad.service.ImageService;
import com.notepad.vo.NoteDetailVO;
import com.notepad.vo.NoteListItemVO;
import com.notepad.entity.Reminder;
import com.notepad.mapper.ReminderMapper;
import com.notepad.vo.ReminderVO;
import com.notepad.entity.Tag;
import com.notepad.mapper.TagMapper;
import com.notepad.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NoteServiceImpl implements NoteService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final NoteMapper noteMapper;
    private final NotebookMapper notebookMapper;
    private final NoteTagMapper noteTagMapper;
    private final TagMapper tagMapper;
    private final ReminderMapper reminderMapper;
    private final ImageService imageService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PageResult<NoteListItemVO> list(Long userId, int page, int pageSize,
                                           Long notebookId, Long tagId, String keyword, String createdDate) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        LocalDateTime[] range = parseDateRange(createdDate);
        IPage<Note> result;
        try {
            result = noteMapper.selectNotePage(new Page<>(safePage, safeSize),
                    userId, notebookId, tagId, keyword, range[0], range[1]);
        } catch (DataAccessException e) {
            if (!isMissingFullTextIndex(e)) {
                throw e;
            }
            log.warn("全文搜索索引尚未安装，临时降级为 LIKE 查询；请执行 005_note_search_index.sql");
            result = noteMapper.selectNotePageLike(new Page<>(safePage, safeSize),
                    userId, notebookId, tagId, keyword, range[0], range[1]);
        }
        return new PageResult<>(result.getTotal(), safePage, safeSize,
                buildListItems(userId, result.getRecords(), false));
    }

    @Override
    public NoteDetailVO detail(Long userId, Long noteId) {
        Note note = requireOwned(userId, noteId);
        NoteDetailVO vo = new NoteDetailVO();
        vo.setId(note.getId());
        vo.setTitle(note.getTitle());
        vo.setPinned(note.getPinned());
        vo.setContent(note.getContent());
        vo.setNotebookId(note.getNotebookId());
        Notebook notebook = notebookMapper.selectById(note.getNotebookId());
        vo.setNotebookName(notebook != null ? notebook.getName() : null);
        vo.setTags(buildTagsByNote(List.of(noteId)).getOrDefault(noteId, List.of()));
        vo.setReminder(loadReminder(noteId));
        vo.setCreateTime(note.getCreateTime());
        vo.setUpdateTime(note.getUpdateTime());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NoteDetailVO create(Long userId, NoteCreateRequest request) {
        Long notebookId = request.getNotebookId();
        if (notebookId == null) {
            notebookId = defaultNotebookId(userId);
        } else {
            requireNotebook(userId, notebookId);
        }
        Note note = new Note();
        note.setUserId(userId);
        note.setNotebookId(notebookId);
        note.setTitle(resolveTitle(request.getTitle(), request.getContent()));
        note.setContent(request.getContent());
        note.setContentText(stripHtml(request.getContent()));
        noteMapper.insert(note);
        saveTags(userId, note.getId(), request.getTagIds());
        imageService.bindImagesToNote(userId, note.getId(), note.getContent());
        NoteDetailVO result = detail(userId, note.getId());
        eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(userId, note.getId()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NoteDetailVO update(Long userId, Long noteId, NoteUpdateRequest request) {
        Note note = requireOwned(userId, noteId);
        note.setTitle(resolveTitle(request.getTitle(), request.getContent()));
        note.setContent(request.getContent());
        note.setContentText(stripHtml(request.getContent()));
        noteMapper.updateById(note);
        saveTags(userId, noteId, request.getTagIds());
        imageService.bindImagesToNote(userId, noteId, request.getContent());
        NoteDetailVO result = detail(userId, noteId);
        eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(userId, noteId));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void move(Long userId, Long noteId, Long notebookId) {
        requireOwned(userId, noteId);
        requireNotebook(userId, notebookId);
        Note update = new Note();
        update.setId(noteId);
        update.setNotebookId(notebookId);
        noteMapper.updateById(update);
        eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(userId, noteId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pin(Long userId, Long noteId, boolean pinned) {
        requireOwned(userId, noteId);
        Note update = new Note();
        update.setId(noteId);
        update.setPinned(pinned ? 1 : 0);
        noteMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long userId, Long noteId, String title) {
        Note note = requireOwned(userId, noteId);
        note.setTitle(title.trim());
        noteMapper.updateById(note);
        eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(userId, noteId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void trash(Long userId, Long noteId) {
        requireOwned(userId, noteId);
        noteMapper.toTrash(userId, noteId);
        reminderMapper.cancelPending(noteId, userId);
        eventPublisher.publishEvent(NoteVectorSyncEvent.delete(userId, noteId));
    }

    @Override
    public PageResult<NoteListItemVO> trashList(Long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        IPage<Note> result = noteMapper.selectTrashPage(new Page<>(safePage, safeSize), userId);
        return new PageResult<>(result.getTotal(), safePage, safeSize,
                buildListItems(userId, result.getRecords(), true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long userId, Long noteId) {
        Note note = noteMapper.selectTrashedById(userId, noteId);
        if (note == null) {
            throw new BusinessException(404, "笔记不存在或不在回收站");
        }
        Long notebookId = note.getNotebookId();
        Notebook notebook = notebookMapper.selectById(notebookId);
        if (notebook == null || !notebook.getUserId().equals(userId)) {
            notebookId = defaultNotebookId(userId);
        }
        noteMapper.restore(userId, noteId, notebookId);
        eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(userId, noteId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteForever(Long userId, Long noteId) {
        Note note = noteMapper.selectTrashedById(userId, noteId);
        if (note == null) {
            throw new BusinessException(404, "笔记不存在或不在回收站");
        }
        noteTagMapper.deleteByNoteId(noteId);
        noteMapper.deleteForever(userId, noteId);
        imageService.deleteImagesByNoteId(userId, noteId);
        eventPublisher.publishEvent(NoteVectorSyncEvent.delete(userId, noteId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearTrash(Long userId) {
        List<Long> ids = noteMapper.selectIdsByUser(userId, 1);
        if (!ids.isEmpty()) {
            noteTagMapper.deleteByNoteIds(ids);
            for (Long id : ids) {
                imageService.deleteImagesByNoteId(userId, id);
            }
        }
        int count = noteMapper.clearTrash(userId);
        ids.forEach(id -> eventPublisher.publishEvent(NoteVectorSyncEvent.delete(userId, id)));
        return count;
    }

    @Override
    public CalendarMarksVO calendarMarks(Long userId, String month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month, MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "month 格式应为 yyyy-MM");
        }
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        return new CalendarMarksVO(month, noteMapper.selectCalendarDates(userId, start, end));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredTrash() {
        LocalDateTime before = LocalDateTime.now().minusDays(30);
        List<Note> expired = noteMapper.selectExpiredNotes(before);
        if (!expired.isEmpty()) {
            noteTagMapper.deleteExpiredTrashTags(before);
            for (Note note : expired) {
                imageService.deleteImagesByNoteId(note.getUserId(), note.getId());
                eventPublisher.publishEvent(NoteVectorSyncEvent.delete(note.getUserId(), note.getId()));
            }
        }
        return noteMapper.deleteExpiredTrash(before);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int backfillContentText() {
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .isNull(Note::getContentText)
                .select(Note::getId, Note::getUserId, Note::getContent));
        int count = 0;
        for (Note note : notes) {
            Note update = new Note();
            update.setId(note.getId());
            update.setContentText(stripHtml(note.getContent()));
            noteMapper.updateById(update);
            eventPublisher.publishEvent(NoteVectorSyncEvent.upsert(note.getUserId(), note.getId()));
            count++;
        }
        return count;
    }

    private Note requireOwned(Long userId, Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    private void requireNotebook(Long userId, Long notebookId) {
        Notebook notebook = notebookMapper.selectById(notebookId);
        if (notebook == null || !notebook.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记本不存在");
        }
    }

    private Long defaultNotebookId(Long userId) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getIsDefault, 1));
        if (notebook == null) {
            throw new BusinessException(500, "默认笔记本不存在，请重新注册");
        }
        return notebook.getId();
    }

    private ReminderVO loadReminder(Long noteId) {
        // 只返回「待提醒」的提醒；已提醒(status=1)/已取消(status=2)的不再展示
        Reminder reminder = reminderMapper.selectOne(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getNoteId, noteId)
                .eq(Reminder::getStatus, 0)
                .orderByDesc(Reminder::getId)
                .last("LIMIT 1"));
        return reminder == null ? null : ReminderVO.from(reminder);
    }

    private List<NoteListItemVO> buildListItems(Long userId, List<Note> notes, boolean withDeleteTime) {
        if (notes.isEmpty()) {
            return List.of();
        }
        Set<Long> notebookIds = notes.stream().map(Note::getNotebookId).collect(Collectors.toSet());
        Map<Long, String> notebookNames = notebookMapper.selectBatchIds(notebookIds).stream()
                .collect(Collectors.toMap(Notebook::getId, Notebook::getName, (a, b) -> a));
        Map<Long, List<TagVO>> tagsByNote = buildTagsByNote(notes.stream().map(Note::getId).toList());
        List<NoteListItemVO> items = new ArrayList<>();
        for (Note note : notes) {
            NoteListItemVO vo = new NoteListItemVO();
            vo.setId(note.getId());
            vo.setTitle(note.getTitle());
            vo.setPinned(note.getPinned());
            vo.setContentPreview(preview(note.getContent()));
            vo.setNotebookId(note.getNotebookId());
            vo.setNotebookName(notebookNames.get(note.getNotebookId()));
            vo.setTags(tagsByNote.getOrDefault(note.getId(), List.of()));
            vo.setCreateTime(note.getCreateTime());
            vo.setUpdateTime(note.getUpdateTime());
            if (withDeleteTime) {
                vo.setDeleteTime(note.getDeleteTime());
            }
            items.add(vo);
        }
        return items;
    }

    private Map<Long, List<TagVO>> buildTagsByNote(List<Long> noteIds) {
        if (noteIds.isEmpty()) {
            return Map.of();
        }
        List<NoteTag> links = noteTagMapper.selectList(new LambdaQueryWrapper<NoteTag>()
                .in(NoteTag::getNoteId, noteIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = links.stream().map(NoteTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tagMap = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t, (a, b) -> a));
        Map<Long, List<TagVO>> result = new HashMap<>();
        for (NoteTag link : links) {
            Tag tag = tagMap.get(link.getTagId());
            if (tag == null) {
                continue;
            }
            result.computeIfAbsent(link.getNoteId(), k -> new ArrayList<>())
                    .add(TagVO.brief(tag.getId(), tag.getName()));
        }
        return result;
    }

    private void saveTags(Long userId, Long noteId, List<Long> tagIds) {
        noteTagMapper.deleteByNoteId(noteId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Set<Long> validTagIds = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .eq(Tag::getUserId, userId))
                .stream().map(Tag::getId).collect(Collectors.toSet());
        for (Long tagId : tagIds.stream().distinct().toList()) {
            if (!validTagIds.contains(tagId)) {
                continue;
            }
            NoteTag link = new NoteTag();
            link.setNoteId(noteId);
            link.setTagId(tagId);
            noteTagMapper.insert(link);
        }
    }

    private LocalDateTime[] parseDateRange(String createdDate) {
        if (!StringUtils.hasText(createdDate)) {
            return new LocalDateTime[]{null, null};
        }
        try {
            LocalDate day = LocalDate.parse(createdDate, DATE_FORMAT);
            return new LocalDateTime[]{day.atStartOfDay(), day.plusDays(1).atStartOfDay()};
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "createdDate 格式应为 yyyy-MM-dd");
        }
    }

    private boolean isMissingFullTextIndex(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("fulltext index")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveTitle(String title, String content) {
        if (StringUtils.hasText(title)) {
            return title.trim();
        }
        String plain = stripHtml(content);
        if (plain.isEmpty()) {
            return "无标题笔记";
        }
        return plain.substring(0, Math.min(50, plain.length()));
    }

    private String preview(String content) {
        String plain = stripHtml(content);
        return plain.length() <= 100 ? plain : plain.substring(0, 100);
    }

    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return html
                .replaceAll("(?s)<[^>]*>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&amp;", "&")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
