package com.notepad.ai.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.notepad.ai.dto.NoteBatchIndexResponse;
import com.notepad.ai.dto.NoteIndexBackupItem;
import com.notepad.ai.dto.NoteIndexBackupVerifyResponse;
import com.notepad.ai.dto.NoteIndexResponse;
import com.notepad.ai.dto.NoteIndexRestoreResponse;
import com.notepad.ai.dto.NoteIndexStatusResponse;
import com.notepad.ai.dto.NoteSearchResult;
import com.notepad.ai.dto.NoteSearchItem;
import com.notepad.ai.dto.RetrievalCandidateTrace;
import com.notepad.ai.dto.RetrievalQueryTrace;
import com.notepad.ai.config.VectorStoreProperties;
import com.notepad.ai.service.vector.IndexSnapshotRepository;
import com.notepad.common.BusinessException;
import com.notepad.entity.Note;
import com.notepad.mapper.NoteMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class NoteVectorService {

    private static final Logger log = LoggerFactory.getLogger(NoteVectorService.class);
    private static final int SEARCH_TOP_K = 5;
    private static final int MULTI_QUERY_CANDIDATE_TOP_K = 15;
    private static final int MAX_RETRIEVAL_QUERIES = 3;
    private static final double MULTI_QUERY_SECONDARY_SCORE_RATIO = 0.72;
    private static final int MAX_CHUNKS_PER_NOTE = 100;
    private static final double SIMILARITY_THRESHOLD = 0.30;
    private static final Pattern MULTI_QUERY_SPLIT_PATTERN = Pattern.compile(
            "[？?；;]+|，(?=(?:并且|并|同时|以及|再|又))");
    private static final Pattern LEADING_CONNECTOR_PATTERN = Pattern.compile(
            "^(?:并且|并|同时|以及|再|又)[，,：:]?");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    /** 必须与 application.yml 的 spring.ai.dashscope.embedding.options.dimensions 保持一致。 */
    private static final int EXPECTED_EMBEDDING_DIMENSION = 1024;
    private static final int MAX_VALIDATION_ISSUES = 5;
    private static final int MOVE_ATTEMPTS = 3;
    private static final long MOVE_RETRY_BACKOFF_MILLIS = 80L;
    private static final String BACKUP_DIRECTORY_NAME = "backups";
    private static final String BACKUP_FILE_PREFIX = "simple-vector-store-";
    private static final String PRE_RESTORE_MARKER = "-pre-restore-";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 安全线：只允许安全字符，配合目录归属校验防止路径穿越。 */
    private static final Pattern SAFE_BACKUP_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.json$");
    /** 归属线：只操作本服务生成的文件，避免误删用户手工放进备份目录的文件。 */
    private static final Pattern MANAGED_BACKUP_NAME = Pattern.compile(
            "^" + BACKUP_FILE_PREFIX + "\\d{8}-\\d{6}(?:-pre-restore)?-[0-9a-f]{8}\\.json$");
    /** 向量片段 ID，形如 note:8:61:chunk:2。 */
    private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile(
            "^note:(\\d+):(\\d+):chunk:(\\d+)$");
    /** 参与向量过滤的 metadata 字段，必须是 JSON 字符串字面量。 */
    private static final List<String> IDENTITY_METADATA_FIELDS =
            List.of("userId", "noteId", "notebookId");

    private final NoteMapper noteMapper;
    private final VectorStore vectorStore;
    private final IndexSnapshotRepository repository;
    private final VectorStoreProperties vectorStoreProperties;
    private final TokenTextSplitter tokenTextSplitter;

    @Value("${notepad.ai.backup-retention:10}")
    private int backupRetention;

    /**
     * 每进程唯一的临时文件后缀。既保证多进程不互相覆盖，也保证
     * SimpleVectorStore.save 内部的 Files.createFile 永远走“创建”分支，
     * 不会撞上上一轮残留的临时文件。
     */
    private final String tempSuffix = ".tmp-" + ProcessHandle.current().pid()
            + "-" + System.nanoTime()
            + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    public NoteVectorService(NoteMapper noteMapper,
                             VectorStore vectorStore,
                             IndexSnapshotRepository repository,
                             VectorStoreProperties vectorStoreProperties,
                             TokenTextSplitter tokenTextSplitter) {
        this.noteMapper = noteMapper;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.vectorStoreProperties = vectorStoreProperties;
        this.tokenTextSplitter = tokenTextSplitter;
    }

    /**
     * 应用启动时恢复上一次持久化的向量数据。
     * 外层再包一层捕获：pgvector 下这里要访问数据库，抖动不应该让整个应用起不来。
     */
    @PostConstruct
    public synchronized void loadVectorStore() {
        try {
            repository.restoreOnStartup();
        }
        catch (RuntimeException exception) {
            log.error("向量数据恢复失败，请重新执行全量索引", exception);
        }
    }

    /**
     * 读取当前用户的一篇真实笔记，切片后生成向量并写入向量库。
     */
    public synchronized NoteIndexResponse indexNote(Long userId, Long noteId) {
        NoteIndexResponse response = indexNoteInternal(userId, noteId);
        saveVectorStore();
        return response;
    }

    private NoteIndexResponse indexNoteInternal(Long userId, Long noteId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null || !userId.equals(note.getUserId())) {
            throw new BusinessException(404, "笔记不存在");
        }

        String title = StringUtils.hasText(note.getTitle()) ? note.getTitle().trim() : "无标题";
        String contentText = StringUtils.hasText(note.getContentText())
                ? note.getContentText().trim()
                : "";
        String sourceText = "标题：" + title + "\n\n正文：\n" + contentText;

        Map<String, Object> sourceMetadata = new HashMap<>();
        sourceMetadata.put("userId", userId.toString());
        sourceMetadata.put("noteId", noteId.toString());
        sourceMetadata.put("title", title);
        if (note.getNotebookId() != null) {
            sourceMetadata.put("notebookId", note.getNotebookId().toString());
        }

        Document source = new Document(sourceText, sourceMetadata);
        List<Document> splitDocuments = tokenTextSplitter.split(source);
        List<Document> indexedDocuments = new ArrayList<>(splitDocuments.size());
        for (int i = 0; i < splitDocuments.size(); i++) {
            Map<String, Object> chunkMetadata = new HashMap<>(sourceMetadata);
            chunkMetadata.put("chunkIndex", i);
            indexedDocuments.add(new Document(
                    chunkId(userId, noteId, i),
                    splitDocuments.get(i).getText(),
                    chunkMetadata));
        }

        // SimpleVectorStore 1.0.3 不支持按元数据过滤删除，因此使用确定性的片段 ID。
        // 先删除这篇笔记可能存在的全部旧片段，再加入新片段，保证重复索引不会产生副本。
        vectorStore.delete(chunkIds(userId, noteId));
        vectorStore.add(indexedDocuments);

        return new NoteIndexResponse(noteId, title, indexedDocuments.size());
    }

    /**
     * 索引当前用户的全部正常笔记。
     */
    public synchronized NoteBatchIndexResponse indexAllNotes(Long userId) {
        List<Long> noteIds = noteMapper.selectIdsByUser(userId, 0);
        int chunkCount = 0;
        for (Long noteId : noteIds) {
            chunkCount += indexNoteInternal(userId, noteId).chunkCount();
        }
        saveVectorStore();
        return new NoteBatchIndexResponse(noteIds.size(), chunkCount);
    }

    /**
     * 统计当前用户的数据库笔记与磁盘向量片段是否一致。
     */
    public synchronized NoteIndexStatusResponse indexStatus(Long userId) {
        Set<Long> activeNoteIds = new HashSet<>(noteMapper.selectIdsByUser(userId, 0));
        Optional<Path> storeFile = repository.storeFile();
        StoreSnapshot snapshot = readStoreSnapshot(userId);
        Set<Long> indexedActiveNoteIds = new HashSet<>(snapshot.noteIds());
        indexedActiveNoteIds.retainAll(activeNoteIds);

        int missingNoteCount = activeNoteIds.size() - indexedActiveNoteIds.size();
        int staleChunkCount = 0;
        for (Long noteId : snapshot.documentNoteIds()) {
            if (!activeNoteIds.contains(noteId)) {
                staleChunkCount++;
            }
        }

        // pgvector 没有本地文件，库本身即持久化载体，所以视为「存在」
        boolean exists = storeFile.map(Files::isRegularFile).orElse(true);
        String state;
        if (!snapshot.readable()) {
            state = "ERROR";
        }
        else if (activeNoteIds.isEmpty() && snapshot.chunkCount() == 0) {
            state = "EMPTY";
        }
        else if (missingNoteCount > 0 || staleChunkCount > 0) {
            state = "NEEDS_REBUILD";
        }
        else {
            state = "HEALTHY";
        }

        NoteIndexStatusResponse response = new NoteIndexStatusResponse(
                activeNoteIds.size(),
                indexedActiveNoteIds.size(),
                snapshot.chunkCount(),
                missingNoteCount,
                staleChunkCount,
                exists,
                storeFile.map(this::fileSize).orElse(0L),
                storeFile.map(this::lastModifiedAt).orElse(null),
                state,
                vectorStoreProperties.getType().name(),
                indexType());
        log.info("event=ai.index.health.checked userId={} state={} noteCount={} chunkCount={} missingNoteCount={} staleChunkCount={} storeFileBytes={}",
                userId, state, response.noteCount(), response.chunkCount(),
                missingNoteCount, staleChunkCount, response.storeFileBytes());
        return response;
    }

    /**
     * 仅清理并重建当前用户的向量，不影响同一向量库中的其他用户。
     */
    public synchronized NoteBatchIndexResponse rebuildUserIndex(Long userId) {
        StoreSnapshot snapshot = readStoreSnapshot(userId);
        if (!snapshot.documentIds().isEmpty()) {
            vectorStore.delete(snapshot.documentIds());
        }

        List<Long> noteIds = noteMapper.selectIdsByUser(userId, 0);
        int chunkCount = 0;
        for (Long noteId : noteIds) {
            chunkCount += indexNoteInternal(userId, noteId).chunkCount();
        }
        saveVectorStore();
        return new NoteBatchIndexResponse(noteIds.size(), chunkCount);
    }

    /**
     * 删除一篇笔记的全部旧片段，并立即保存本地向量文件。
     */
    public synchronized void deleteNote(Long userId, Long noteId) {
        vectorStore.delete(chunkIds(userId, noteId));
        saveVectorStore();
    }

    /**
     * 只搜索当前用户的向量数据，防止不同用户之间的笔记互相泄露。
     */
    public List<NoteSearchItem> search(Long userId, String question) {
        return search(userId, question, null, null);
    }

    /**
     * 搜索当前用户的向量数据；noteId 不为空时进一步限定为当前笔记。
     */
    public List<NoteSearchItem> search(Long userId, String question, Long noteId) {
        return search(userId, question, noteId, null);
    }

    /**
     * 搜索当前用户的向量数据，并按传入的笔记或笔记本范围继续收窄。
     */
    public List<NoteSearchItem> search(
            Long userId,
            String question,
            Long noteId,
            Long notebookId) {
        return searchWithTrace(userId, question, noteId, notebookId).items();
    }

    /**
     * 返回检索结果及每个子查询的候选轨迹，供回归测试解释失败原因。
     */
    public NoteSearchResult searchWithTrace(
            Long userId,
            String question,
            Long noteId,
            Long notebookId) {
        long startedAt = System.nanoTime();
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op scopeExpression =
                filterBuilder.eq("userId", userId.toString());
        if (notebookId != null) {
            scopeExpression = filterBuilder.and(
                    scopeExpression,
                    filterBuilder.eq("notebookId", notebookId.toString()));
        }
        if (noteId != null) {
            scopeExpression = filterBuilder.and(
                    scopeExpression,
                    filterBuilder.eq("noteId", noteId.toString()));
        }
        List<String> retrievalQueries = splitRetrievalQueries(question);
        List<List<Document>> matchGroups = new ArrayList<>(retrievalQueries.size());
        List<Document> allMatches = new ArrayList<>(retrievalQueries.size() * SEARCH_TOP_K);
        for (String retrievalQuery : retrievalQueries) {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(retrievalQuery)
                    .topK(retrievalQueries.size() > 1
                            ? MULTI_QUERY_CANDIDATE_TOP_K
                            : SEARCH_TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression(scopeExpression.build())
                    .build();
            List<Document> matches = vectorStore.similaritySearch(searchRequest);
            matchGroups.add(matches);
            allMatches.addAll(matches);
        }

        Map<Long, Note> candidateNotes = loadCandidateNotes(allMatches);
        List<List<NoteSearchItem>> scopedGroups = matchGroups.stream()
                .map(matches -> toScopedItems(
                        matches,
                        candidateNotes,
                        userId,
                        noteId,
                        notebookId))
                .toList();

        List<NoteSearchItem> results = scopedGroups.size() == 1
                ? scopedGroups.get(0)
                : mergeMultiQueryResults(scopedGroups);
        NoteSearchResult result = new NoteSearchResult(
                results,
                buildRetrievalTraces(retrievalQueries, scopedGroups, results));
        log.info(
                "event=ai.retrieval.completed userId={} scope={} queryCount={} resultCount={} durationMs={}",
                userId,
                searchScope(noteId, notebookId),
                retrievalQueries.size(),
                results.size(),
                elapsedMillis(startedAt));
        return result;
    }

    private String searchScope(Long noteId, Long notebookId) {
        if (noteId != null) {
            return "note:" + noteId;
        }
        if (notebookId != null) {
            return "notebook:" + notebookId;
        }
        return "all";
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private List<String> splitRetrievalQueries(String question) {
        String normalizedQuestion = question.trim();
        String[] rawParts = MULTI_QUERY_SPLIT_PATTERN.split(normalizedQuestion);
        List<String> queries = new ArrayList<>(MAX_RETRIEVAL_QUERIES);
        for (String rawPart : rawParts) {
            String part = LEADING_CONNECTOR_PATTERN.matcher(rawPart.trim()).replaceFirst("").trim();
            if (part.length() >= 4 && !queries.contains(part)) {
                queries.add(part);
            }
            if (queries.size() >= MAX_RETRIEVAL_QUERIES) {
                break;
            }
        }
        return queries.size() > 1 ? queries : List.of(normalizedQuestion);
    }

    private List<NoteSearchItem> toScopedItems(
            List<Document> matches,
            Map<Long, Note> candidateNotes,
            Long userId,
            Long requestedNoteId,
            Long requestedNotebookId) {
        List<NoteSearchItem> results = new ArrayList<>(SEARCH_TOP_K);
        for (Document document : matches) {
            Long matchedNoteId = documentNoteId(document);
            // 向量元数据可能因异步移动笔记而短暂滞后，最终以数据库实时归属为准。
            if (matchedNoteId == null
                    || !isDocumentInScope(
                            matchedNoteId,
                            candidateNotes,
                            userId,
                            requestedNoteId,
                            requestedNotebookId)) {
                continue;
            }
            results.add(new NoteSearchItem(
                    matchedNoteId,
                    document.getMetadata().get("title").toString(),
                    document.getText(),
                    document.getScore()));
        }
        return results;
    }

    private List<NoteSearchItem> mergeMultiQueryResults(List<List<NoteSearchItem>> groups) {
        LinkedHashMap<Long, NoteSearchItem> selectedByNote = new LinkedHashMap<>();
        List<NoteSearchItem> primarySelections = new ArrayList<>(groups.size());
        Map<Long, Integer> topNoteFrequency = new HashMap<>();
        for (List<NoteSearchItem> group : groups) {
            if (!group.isEmpty()) {
                topNoteFrequency.merge(group.get(0).noteId(), 1, Integer::sum);
            }
        }

        // 每个子问题先保留一篇尚未入选的最高分笔记，避免某个子问题完全丢失。
        for (List<NoteSearchItem> group : groups) {
            NoteSearchItem primary = null;
            for (NoteSearchItem item : group) {
                if (!selectedByNote.containsKey(item.noteId())) {
                    selectedByNote.put(item.noteId(), item);
                    primary = item;
                    break;
                }
            }
            primarySelections.add(primary);
        }

        // 只有多个子问题的最高分都被同一篇笔记占据，并且当前子问题最终仍选择了
        // 这篇公共第一名时，才为它补一篇分数足够接近的不同笔记。这样可以解决
        // 长笔记霸占多路召回，同时不会给本来已各自命中的子问题制造额外引用。
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            List<NoteSearchItem> group = groups.get(groupIndex);
            if (selectedByNote.size() >= SEARCH_TOP_K || group.isEmpty()) {
                break;
            }
            NoteSearchItem topItem = group.get(0);
            NoteSearchItem primary = primarySelections.get(groupIndex);
            if (primary == null
                    || !primary.noteId().equals(topItem.noteId())
                    || topNoteFrequency.getOrDefault(topItem.noteId(), 0) < 2
                    || topItem.score() == null) {
                continue;
            }
            double secondaryScoreFloor = topItem.score() * MULTI_QUERY_SECONDARY_SCORE_RATIO;
            for (NoteSearchItem item : group) {
                if (item.score() == null || item.score() < secondaryScoreFloor) {
                    break;
                }
                if (!selectedByNote.containsKey(item.noteId())) {
                    selectedByNote.put(item.noteId(), item);
                    break;
                }
            }
        }
        return List.copyOf(selectedByNote.values());
    }

    private List<RetrievalQueryTrace> buildRetrievalTraces(
            List<String> queries,
            List<List<NoteSearchItem>> groups,
            List<NoteSearchItem> selectedItems) {
        Set<Long> selectedNoteIds = new HashSet<>();
        for (NoteSearchItem item : selectedItems) {
            selectedNoteIds.add(item.noteId());
        }

        List<RetrievalQueryTrace> traces = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            LinkedHashMap<Long, RetrievalCandidateTrace> candidatesByNote = new LinkedHashMap<>();
            for (NoteSearchItem item : groups.get(i)) {
                candidatesByNote.putIfAbsent(
                        item.noteId(),
                        new RetrievalCandidateTrace(
                                item.noteId(),
                                item.title(),
                                item.score(),
                                selectedNoteIds.contains(item.noteId())));
            }
            traces.add(new RetrievalQueryTrace(
                    queries.get(i),
                    List.copyOf(candidatesByNote.values())));
        }
        return traces;
    }

    private Map<Long, Note> loadCandidateNotes(List<Document> documents) {
        Set<Long> noteIds = new HashSet<>();
        for (Document document : documents) {
            Long noteId = documentNoteId(document);
            if (noteId != null) {
                noteIds.add(noteId);
            }
        }
        if (noteIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Note> notesById = new HashMap<>();
        for (Note note : noteMapper.selectBatchIds(noteIds)) {
            notesById.put(note.getId(), note);
        }
        return notesById;
    }

    private boolean isDocumentInScope(
            Long matchedNoteId,
            Map<Long, Note> candidateNotes,
            Long userId,
            Long requestedNoteId,
            Long requestedNotebookId) {
        if (requestedNoteId != null && !requestedNoteId.equals(matchedNoteId)) {
            return false;
        }

        Note note = candidateNotes.get(matchedNoteId);
        return note != null
                && userId.equals(note.getUserId())
                && (requestedNotebookId == null
                        || requestedNotebookId.equals(note.getNotebookId()));
    }

    private Long documentNoteId(Document document) {
        Object metadataNoteId = document.getMetadata().get("noteId");
        if (metadataNoteId == null) {
            return null;
        }
        try {
            return Long.valueOf(metadataNoteId.toString());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> chunkIds(Long userId, Long noteId) {
        List<String> ids = new ArrayList<>(MAX_CHUNKS_PER_NOTE);
        for (int i = 0; i < MAX_CHUNKS_PER_NOTE; i++) {
            ids.add(chunkId(userId, noteId, i));
        }
        return ids;
    }

    private String chunkId(Long userId, Long noteId, int chunkIndex) {
        return "note:" + userId + ":" + noteId + ":chunk:" + chunkIndex;
    }

    /**
     * 原子保存向量库：先写同目录的临时文件，再整体替换目标文件。
     * SimpleVectorStore.save 会直接截断目标文件再写入，进程中断就会留下半个 JSON，
     * 而启动时加载失败会退化成空向量库。先写临时文件可以保证磁盘上任何时刻
     * 都是一份完整可解析的索引。
     */
    private void saveVectorStore() {
        if (!repository.fileBacked()) {
            // pgvector：写入已经在 SQL 层持久化，没有「落盘」这一步
            return;
        }
        Path storePath = repository.storeFile().orElseThrow();
        Path tempPath = temporarySibling(storePath);
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            repository.exportSnapshot(tempPath);
            forceFile(tempPath);
            moveIntoPlace(tempPath, storePath);
            log.debug("event=ai.index.store.saved bytes={} file={}",
                    fileSize(storePath), storePath.getFileName());
        }
        catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("保存本地向量文件失败: " + storePath, exception);
        }
        finally {
            deleteQuietly(tempPath);
        }
    }

    /**
     * 临时文件必须与目标同目录，跨文件系统时 ATOMIC_MOVE 不成立。
     */
    private Path temporarySibling(Path target) {
        return target.resolveSibling(target.getFileName() + tempSuffix);
    }

    /**
     * 用临时文件整体替换目标文件。AtomicMoveNotSupportedException 是 FileSystemException
     * 的子类，必须在这里就地降级，否则会被外层的 catch (IOException) 一起吞掉。
     */
    private void moveIntoPlace(Path source, Path target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(source, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                catch (AtomicMoveNotSupportedException exception) {
                    log.warn("event=ai.index.store.atomic_move_unsupported file={}",
                            target.getFileName());
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
            catch (AccessDeniedException exception) {
                // Windows 上目标文件可能被 IDE、杀毒软件或其它句柄短暂占用。
                lastFailure = exception;
                sleepQuietly(MOVE_RETRY_BACKOFF_MILLIS * (attempt + 1));
            }
        }
        throw lastFailure;
    }

    /**
     * 尽力把内容刷到磁盘；失败只记日志，不影响本次保存的语义。
     */
    private void forceFile(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        catch (IOException exception) {
            log.warn("event=ai.index.store.fsync_failed file={}", path.getFileName());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean deleteQuietly(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        }
        catch (IOException exception) {
            log.warn("event=ai.index.store.temp_delete_failed file={}", path.getFileName());
            return false;
        }
    }

    // ==================== 索引备份与恢复 ====================

    /**
     * 创建一份当前向量库的完整备份。
     * 备份是全库快照：SimpleVectorStore 用一个文件承载全部用户的片段，因此这里不是当前用户的子集。
     */
    public synchronized NoteIndexBackupItem createIndexBackup(Long userId) {
        long startedAt = System.nanoTime();
        log.info("event=ai.index.backup.started userId={} trigger=manual", userId);

        Path directory = backupDirectory();
        Path backupPath = directory.resolve(backupFileName(false));
        Path stagedSnapshot = null;
        try {
            Files.createDirectories(directory);

            // 统一先得到一份「待备份的快照文件」：file-backed 直接用现有文件，
            // pgvector 先导出成同格式的临时文件。校验与后续流程完全共用。
            Path snapshot;
            if (repository.fileBacked()) {
                snapshot = repository.storeFile().orElseThrow();
                if (!Files.isRegularFile(snapshot)) {
                    log.error("event=ai.index.backup.failed userId={} outcome=store_missing durationMs={}",
                            userId, elapsedMillis(startedAt));
                    throw new BusinessException(400, "当前没有可备份的索引文件，请先执行一键重建");
                }
            }
            else {
                stagedSnapshot = directory.resolve(backupFileName(false) + ".staged");
                repository.exportSnapshot(stagedSnapshot);
                snapshot = stagedSnapshot;
            }

            BackupValidation validation = validateStoreFile(snapshot);
            if (!validation.valid()) {
                log.error("event=ai.index.backup.failed userId={} outcome=index_invalid firstIssue={} durationMs={}",
                        userId, validation.issueMessage(), elapsedMillis(startedAt));
                throw new BusinessException(500, "当前索引文件不完整，无法备份：" + validation.issueMessage());
            }

            if (stagedSnapshot != null) {
                Files.move(stagedSnapshot, backupPath);
                stagedSnapshot = null;
            }
            else {
                // 不传 REPLACE_EXISTING：文件名唯一，已存在说明有问题，应当直接失败。
                Files.copy(snapshot, backupPath);
            }
            forceFile(backupPath);

            pruneBackups();
            NoteIndexBackupItem item = toBackupItem(backupPath);
            log.info("event=ai.index.backup.completed userId={} fileName={} bytes={} entryCount={} userCount={} durationMs={}",
                    userId, item.fileName(), item.fileBytes(), validation.entryCount(),
                    validation.userCount(), elapsedMillis(startedAt));
            return item;
        }
        catch (IOException exception) {
            deleteQuietly(backupPath);
            log.error("event=ai.index.backup.failed userId={} outcome=io_error durationMs={}",
                    userId, elapsedMillis(startedAt), exception);
            throw new BusinessException(500, "创建索引备份失败");
        }
        finally {
            deleteQuietly(stagedSnapshot);
        }
    }

    /**
     * 列出全部备份。只读文件系统元数据，不做内容校验，
     * 避免一次请求把多份 3MB 文件全部解析一遍。
     */
    public List<NoteIndexBackupItem> listIndexBackups(Long userId) {
        List<NoteIndexBackupItem> items = new ArrayList<>();
        for (Path path : backupFiles()) {
            items.add(toBackupItem(path));
        }
        // 取不到修改时间时排在最后，不能让比较器抛空指针。
        items.sort(Comparator.comparing(
                NoteIndexBackupItem::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        log.debug("event=ai.index.backup.listed userId={} count={}", userId, items.size());
        return items;
    }

    /**
     * 只校验备份文件，不产生任何副作用，供前端在发起恢复前预检。
     */
    public NoteIndexBackupVerifyResponse verifyIndexBackup(Long userId, String rawFileName) {
        Path path = resolveBackupPath(rawFileName);
        BackupValidation validation = validateStoreFile(path);
        log.info("event=ai.index.validate.completed userId={} fileName={} valid={} entryCount={} userCount={} firstIssue={}",
                userId, path.getFileName(), validation.valid(), validation.entryCount(),
                validation.userCount(), validation.issueMessage());
        return new NoteIndexBackupVerifyResponse(
                path.getFileName().toString(),
                validation.valid(),
                validation.entryCount(),
                validation.userCount(),
                validation.issueMessage());
    }

    /**
     * 从备份恢复整个向量库。
     *
     * 一次恢复会把所有用户的索引一起回退到备份时间点，因此恢复前必须自动留档。
     *
     * 步骤顺序刻意设计成“先载入内存、后写入磁盘”：SimpleVectorStore.load 只有解析成功
     * 才会替换内部引用，失败时内存中的索引原封不动。这样最可能失败的场景
     * （备份内容无法反序列化）完全没有副作用，不需要任何回滚。
     */
    public synchronized NoteIndexRestoreResponse restoreIndexBackup(Long userId, String rawFileName) {
        long startedAt = System.nanoTime();
        Path candidate = resolveBackupPath(rawFileName);
        log.info("event=ai.index.restore.started userId={} fileName={} backupBytes={}",
                userId, candidate.getFileName(), fileSize(candidate));

        BackupValidation validation = validateStoreFile(candidate);
        if (!validation.valid()) {
            log.warn("event=ai.index.restore.rejected userId={} fileName={} reason=invalid_backup firstIssue={}",
                    userId, candidate.getFileName(), validation.issueMessage());
            throw new BusinessException(400, "备份文件校验失败：" + validation.issueMessage());
        }
        log.info("event=ai.index.validate.completed userId={} fileName={} valid=true entryCount={} userCount={} dimension={}",
                userId, candidate.getFileName(), validation.entryCount(),
                validation.userCount(), EXPECTED_EMBEDDING_DIMENSION);

        Optional<Path> storeFile = repository.storeFile();
        Path snapshotPath = createPreRestoreSnapshot(userId);

        try {
            // 直接读取备份文件本身；导入只读不移动，备份不会被消耗掉。
            repository.importSnapshot(candidate);
        }
        catch (RuntimeException exception) {
            log.error("event=ai.index.restore.failed userId={} fileName={} outcome=load_failed rolledBack=false durationMs={}",
                    userId, candidate.getFileName(), elapsedMillis(startedAt), exception);
            throw new BusinessException(500, "备份内容无法载入，当前索引未受影响");
        }

        try {
            saveVectorStore();
        }
        catch (RuntimeException exception) {
            // 内存里已经是备份内容、磁盘上还是恢复前的版本，必须把两边都退回留档。
            boolean rolledBack = rollbackToSnapshot(snapshotPath);
            log.error("event=ai.index.restore.failed userId={} fileName={} outcome=save_failed rolledBack={} durationMs={}",
                    userId, candidate.getFileName(), rolledBack, elapsedMillis(startedAt), exception);
            throw new BusinessException(500, rolledBack
                    ? "写入索引文件失败，已回滚到恢复前的索引"
                    : "写入索引文件失败，请使用留档文件或一键重建恢复");
        }

        long storeBytes = storeFile.map(this::fileSize).orElse(0L);
        NoteIndexStatusResponse status = indexStatus(userId);
        log.info("event=ai.index.restore.completed userId={} fileName={} entryCount={} bytes={} state={} durationMs={}",
                userId, candidate.getFileName(), validation.entryCount(),
                storeBytes, status.state(), elapsedMillis(startedAt));
        return new NoteIndexRestoreResponse(
                candidate.getFileName().toString(),
                validation.entryCount(),
                storeBytes,
                snapshotPath == null ? null : snapshotPath.getFileName().toString(),
                status);
    }

    /**
     * 恢复前把当前索引复制一份留档。这里必须是复制而不是移动：
     * 留档文件要在整个恢复过程中始终存在，写入失败时才有东西可以回退。
     */
    private Path createPreRestoreSnapshot(Long userId) {
        Optional<Path> storeFile = repository.storeFile();
        if (storeFile.isPresent() && !Files.isRegularFile(storeFile.get())) {
            log.warn("event=ai.index.restore.snapshot_skipped userId={} reason=store_missing", userId);
            return null;
        }

        Path directory = backupDirectory();
        Path snapshotPath = directory.resolve(backupFileName(true));
        try {
            Files.createDirectories(directory);
            if (repository.fileBacked()) {
                Files.copy(storeFile.orElseThrow(), snapshotPath);
            }
            else {
                // pgvector 没有本地文件：导出成同格式的快照，
                // 让「恢复前自动留档」这条安全属性在两种存储下完全一致。
                repository.exportSnapshot(snapshotPath);
            }
            forceFile(snapshotPath);
        }
        catch (IOException exception) {
            deleteQuietly(snapshotPath);
            log.error("event=ai.index.restore.failed userId={} outcome=snapshot_failed", userId, exception);
            throw new BusinessException(500, "恢复前留档失败，已取消恢复");
        }

        log.info("event=ai.index.restore.snapshot_created userId={} fileName={} bytes={}",
                userId, snapshotPath.getFileName(), fileSize(snapshotPath));
        return snapshotPath;
    }

    /**
     * 写入失败时把内存和磁盘一起退回留档。留档文件本身保留，供人工再次回退。
     */
    private boolean rollbackToSnapshot(Path snapshotPath) {
        if (snapshotPath == null) {
            return false;
        }

        try {
            if (repository.fileBacked()) {
                Path storePath = repository.storeFile().orElseThrow();
                Path tempPath = temporarySibling(storePath);
                try {
                    Files.copy(snapshotPath, tempPath, StandardCopyOption.REPLACE_EXISTING);
                    moveIntoPlace(tempPath, storePath);
                }
                finally {
                    deleteQuietly(tempPath);
                }
            }
            repository.importSnapshot(snapshotPath);
            log.error("event=ai.index.restore.rolled_back outcome=success fileName={}",
                    snapshotPath.getFileName());
            return true;
        }
        catch (IOException | RuntimeException exception) {
            log.error("event=ai.index.restore.rolled_back outcome=failed fileName={}",
                    snapshotPath.getFileName(), exception);
            return false;
        }
    }

    /**
     * 把外部传入的文件名解析成备份目录内的真实路径。
     * 两重校验：安全字符防路径穿越，归属正则保证只操作本服务生成的文件。
     */
    private Path resolveBackupPath(String rawFileName) {
        String fileName = rawFileName == null ? "" : rawFileName.trim();
        if (!SAFE_BACKUP_NAME.matcher(fileName).matches()
                || !MANAGED_BACKUP_NAME.matcher(fileName).matches()) {
            log.warn("event=ai.index.restore.rejected reason=unsafe_name fileName={}", fileName);
            throw new BusinessException(404, "备份文件不存在");
        }

        Path directory = backupDirectory();
        Path candidate = directory.resolve(fileName).normalize();
        if (!directory.equals(candidate.getParent())) {
            log.warn("event=ai.index.restore.rejected reason=outside_backup_directory fileName={}", fileName);
            throw new BusinessException(400, "备份文件名不合法");
        }
        if (!Files.isRegularFile(candidate)) {
            log.warn("event=ai.index.restore.rejected reason=missing fileName={}", fileName);
            throw new BusinessException(404, "备份文件不存在");
        }
        return candidate;
    }

    /**
     * 流式校验一份向量库文件是否可以安全恢复。
     * 全程只走 JsonParser 的 token，embedding 数组只数元素个数，绝不读进内存。
     */
    private BackupValidation validateStoreFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return BackupValidation.invalid(0, 0, "备份文件不存在");
        }
        if (fileSize(path) < 2) {
            return BackupValidation.invalid(0, 0, "备份文件为空");
        }

        List<String> issues = new ArrayList<>(MAX_VALIDATION_ISSUES);
        Set<Long> userIds = new HashSet<>();
        int entryCount = 0;
        try (JsonParser parser = JSON_FACTORY.createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return BackupValidation.invalid(0, 0, "备份文件结构不正确");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String documentId = parser.currentName();
                entryCount++;
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    addIssue(issues, "第 " + entryCount + " 条片段不是对象");
                    parser.skipChildren();
                    continue;
                }

                ChunkShape shape = readChunkShape(parser);
                collectChunkIssues(documentId, shape, issues);
                if (shape.userId() != null) {
                    userIds.add(shape.userId());
                }
                if (issues.size() >= MAX_VALIDATION_ISSUES) {
                    break;
                }
            }
        }
        catch (IOException | RuntimeException exception) {
            log.warn("event=ai.index.validate.failed fileName={} reason=unparsable",
                    path.getFileName(), exception);
            return BackupValidation.invalid(entryCount, userIds.size(), "备份文件不是合法的 JSON，无法恢复");
        }

        if (!issues.isEmpty()) {
            log.warn("event=ai.index.validate.failed fileName={} entryCount={} firstIssue={}",
                    path.getFileName(), entryCount, issues.get(0));
            return BackupValidation.invalid(entryCount, userIds.size(), issues.get(0));
        }
        if (entryCount == 0) {
            return BackupValidation.invalid(0, 0, "备份文件不含任何向量片段，拒绝恢复");
        }
        return BackupValidation.valid(entryCount, userIds.size());
    }

    /**
     * 读取单个片段的形状信息。embedding 只统计元素个数，浮点值解析后立即丢弃。
     */
    private ChunkShape readChunkShape(JsonParser parser) throws IOException {
        String innerId = null;
        String text = null;
        ScalarMetadata metadata = null;
        boolean embeddingSeen = false;
        int dimension = 0;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            // 必须先前进到字段值 token。currentName() 只在 FIELD_NAME 上有效，
            // 直接读值会读到 FIELD_NAME 本身，导致所有字段都被判为缺失。
            parser.nextToken();
            switch (field) {
                case "id" -> innerId = readStringOrNull(parser);
                case "text" -> text = readStringOrNull(parser);
                case "embedding" -> {
                    embeddingSeen = true;
                    dimension = countArrayElements(parser);
                }
                case "metadata" -> metadata = readScalarMap(parser);
                default -> parser.skipChildren();
            }
        }

        Long userId = null;
        if (metadata != null) {
            userId = parseLongOrNull(metadata.values().get("userId"));
        }
        return new ChunkShape(innerId, text, metadata, embeddingSeen, dimension, userId);
    }

    /**
     * 校验单个片段。
     *
     * “用户数据边界”指的是 documentId 里的 userId/noteId 与它自己的 metadata 自洽，
     * 而不是“只属于当前用户”——备份是全库快照，一份文件包含所有用户的片段。
     * 这条不变量必须成立：检索按 metadata.userId 过滤，而重建与删除按 documentId 前缀操作，
     * 两者不一致会导致跨用户引用或残留孤儿片段。
     */
    private void collectChunkIssues(String documentId, ChunkShape shape, List<String> issues) {
        Matcher idMatcher = DOCUMENT_ID_PATTERN.matcher(documentId);
        boolean idMatches = idMatcher.matches();
        if (!idMatches) {
            addIssue(issues, "片段 ID 格式不正确：" + documentId);
        }
        if (shape.innerId() == null || !shape.innerId().equals(documentId)) {
            addIssue(issues, "片段 ID 与内容不一致：" + documentId);
        }
        if (shape.text() == null || shape.text().isBlank()) {
            addIssue(issues, "片段缺少正文：" + documentId);
        }
        if (!shape.embeddingSeen()) {
            addIssue(issues, "片段缺少向量：" + documentId);
        }
        else if (shape.dimension() != EXPECTED_EMBEDDING_DIMENSION) {
            addIssue(issues, "片段向量维度为 " + shape.dimension()
                    + "，期望 " + EXPECTED_EMBEDDING_DIMENSION + "：" + documentId);
        }

        ScalarMetadata metadata = shape.metadata();
        if (metadata == null || metadata.values().isEmpty()) {
            addIssue(issues, "片段缺少元数据：" + documentId);
            return;
        }
        Map<String, String> values = metadata.values();

        String title = values.get("title");
        if (title == null || title.isBlank()) {
            // 检索结果会直接读取 metadata.title，缺失会让之后每一次问答都抛空指针。
            addIssue(issues, "片段缺少标题：" + documentId);
        }

        // 用于向量过滤的身份字段必须是 JSON 字符串字面量。
        // 写成数字（{"userId": 8}）能通过其余全部校验，但 pgvector 的 jsonpath 过滤
        // 是类型敏感的字符串比较（$.userId == "8"），检索时会永远不命中且不报错 ——
        // 属于最难排查的一类静默失效，所以在恢复入口就拒掉。
        for (String identityField : IDENTITY_METADATA_FIELDS) {
            if (metadata.nonStringFields().contains(identityField)) {
                addIssue(issues, "片段元数据 " + identityField + " 必须是字符串：" + documentId);
            }
        }

        if (!idMatches) {
            return;
        }

        String expectedUserId = idMatcher.group(1);
        String expectedNoteId = idMatcher.group(2);
        String expectedChunkIndex = idMatcher.group(3);
        if (!expectedUserId.equals(values.get("userId"))) {
            addIssue(issues, "片段用户边界不一致：" + documentId
                    + " metadata.userId=" + values.get("userId"));
        }
        if (!expectedNoteId.equals(values.get("noteId"))) {
            addIssue(issues, "片段笔记边界不一致：" + documentId
                    + " metadata.noteId=" + values.get("noteId"));
        }
        if (values.containsKey("chunkIndex")
                && !expectedChunkIndex.equals(values.get("chunkIndex"))) {
            addIssue(issues, "片段分片序号不一致：" + documentId);
        }
    }

    /**
     * 只统计数组元素个数，不把浮点值读进内存。
     * 返回 -1 表示不是数组、数组不是扁平的纯数字序列，或文件被截断。
     */
    private int countArrayElements(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                parser.skipChildren();
            }
            return -1;
        }

        boolean flat = true;
        int count = 0;
        // 无论是否合法都要消费到 END_ARRAY，否则解析位置会错乱，后续片段全部误判。
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken token = parser.currentToken();
            if (token == null) {
                return -1;
            }
            if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                parser.skipChildren();
                flat = false;
                continue;
            }
            if (!token.isNumeric()) {
                // 非数字元素能骗过元素个数校验，却会让 load() 反序列化失败。
                flat = false;
            }
            count++;
        }
        return flat ? count : -1;
    }

    /**
     * 只收取 metadata 里的标量字段，嵌套结构直接跳过。
     * 同时记录哪些字段的值不是字符串字面量 —— pgvector 的 jsonpath 过滤是
     * 类型敏感的字符串比较，数值型元数据能通过其余校验却永远不命中。
     */
    private ScalarMetadata readScalarMap(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.START_OBJECT) {
            if (token == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
            return null;
        }

        Map<String, String> values = new HashMap<>();
        Set<String> nonStringFields = new HashSet<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (valueToken != null && valueToken.isScalarValue()) {
                values.put(field, parser.getText());
                if (valueToken != JsonToken.VALUE_STRING) {
                    nonStringFields.add(field);
                }
            }
            else if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
        }
        return new ScalarMetadata(values, nonStringFields);
    }

    private String readStringOrNull(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            parser.skipChildren();
        }
        return null;
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private void addIssue(List<String> issues, String issue) {
        if (issues.size() < MAX_VALIDATION_ISSUES) {
            issues.add(issue);
        }
    }

    /**
     * 备份目录由索引文件所在目录推导，不额外配置，避免和 storePath() 的工作目录解析逻辑冲突。
     * 索引文件位于该目录的上一级，所以任何清理都不可能误删正在使用的索引。
     */
    private Path backupDirectory() {
        return repository.dataDirectory().resolve(BACKUP_DIRECTORY_NAME).normalize();
    }

    private String backupFileName(boolean preRestore) {
        return BACKUP_FILE_PREFIX
                + BACKUP_TIMESTAMP_FORMAT.format(LocalDateTime.now())
                + (preRestore ? PRE_RESTORE_MARKER : "-")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + ".json";
    }

    private List<Path> backupFiles() {
        Path directory = backupDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> MANAGED_BACKUP_NAME.matcher(path.getFileName().toString()).matches())
                    .toList();
        }
        catch (IOException exception) {
            log.warn("event=ai.index.backup.list_failed directory={}", directory, exception);
            return List.of();
        }
    }

    private NoteIndexBackupItem toBackupItem(Path path) {
        String fileName = path.getFileName().toString();
        return new NoteIndexBackupItem(
                fileName,
                fileSize(path),
                lastModifiedAt(path),
                fileName.contains(PRE_RESTORE_MARKER) ? "pre-restore" : "manual");
    }

    /**
     * 只保留最近 backupRetention 份备份。仅在成功创建新备份后调用；启动时不清理，
     * 因为磁盘紧张时启动清理反而会删掉可回退的恢复点。
     */
    private void pruneBackups() {
        int retention = Math.max(1, backupRetention);
        List<Path> files = new ArrayList<>(backupFiles());
        if (files.size() <= retention) {
            return;
        }

        // backupFiles() 已保证文件名匹配 MANAGED_BACKUP_NAME，
        // 去掉固定前缀后以 yyyyMMdd-HHmmss 开头，可以直接按字典序排出新旧。
        files.sort(Comparator.comparing(path -> path.getFileName().toString()
                .substring(BACKUP_FILE_PREFIX.length())));
        int removed = 0;
        for (int i = 0; i < files.size() - retention; i++) {
            if (deleteQuietly(files.get(i))) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("event=ai.index.backup.pruned removedCount={} retention={}", removed, retention);
        }
    }

    /**
     * 读取当前用户在本存储中的片段清单。
     *
     * 片段 ID 的格式（note:userId:noteId:chunk:n）是服务层约定，所以这里仍然用
     * parseNoteId 过滤一遍 —— 存储层只负责「按前缀取出 ID」。
     */
    private StoreSnapshot readStoreSnapshot(Long userId) {
        String prefix = "note:" + userId + ":";
        Set<Long> noteIds = new HashSet<>();
        List<Long> documentNoteIds = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();
        try {
            for (String documentId : repository.documentIds(userId)) {
                Long noteId = parseNoteId(documentId, prefix);
                if (noteId != null) {
                    noteIds.add(noteId);
                    documentNoteIds.add(noteId);
                    documentIds.add(documentId);
                }
            }
            return new StoreSnapshot(noteIds, documentNoteIds, documentIds, documentIds.size(), true);
        }
        catch (RuntimeException exception) {
            log.warn("读取向量索引状态失败: userId={}", userId, exception);
            return StoreSnapshot.empty(false);
        }
    }

    /**
     * 当前生效的向量索引档位。SimpleVectorStore 不是索引型存储，返回 null。
     */
    private String indexType() {
        return vectorStoreProperties.getType() == VectorStoreProperties.Type.pgvector
                ? vectorStoreProperties.getPgvector().getIndexType()
                : null;
    }

    private Long parseNoteId(String documentId, String prefix) {
        if (!documentId.startsWith(prefix)) {
            return null;
        }
        int chunkMarker = documentId.indexOf(":chunk:", prefix.length());
        if (chunkMarker < 0) {
            return null;
        }
        try {
            return Long.valueOf(documentId.substring(prefix.length(), chunkMarker));
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        }
        catch (IOException exception) {
            return 0L;
        }
    }

    private Instant lastModifiedAt(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toInstant() : null;
        }
        catch (IOException exception) {
            return null;
        }
    }

    /**
     * 备份文件的校验结论；valid 为 false 时 issueMessage 是首个失败原因。
     */
    private record BackupValidation(boolean valid, int entryCount, int userCount, String issueMessage) {

        private static BackupValidation valid(int entryCount, int userCount) {
            return new BackupValidation(true, entryCount, userCount, null);
        }

        private static BackupValidation invalid(int entryCount, int userCount, String issueMessage) {
            return new BackupValidation(false, entryCount, userCount, issueMessage);
        }
    }

    /**
     * 单个向量片段在校验阶段需要读取的形状信息。
     */
    private record ChunkShape(
            String innerId,
            String text,
            ScalarMetadata metadata,
            boolean embeddingSeen,
            int dimension,
            Long userId
    ) {
    }

    /**
     * metadata 里的标量字段，以及其中哪些字段的值不是字符串字面量。
     */
    private record ScalarMetadata(Map<String, String> values, Set<String> nonStringFields) {
    }

    private record StoreSnapshot(
            Set<Long> noteIds,
            List<Long> documentNoteIds,
            List<String> documentIds,
            int chunkCount,
            boolean readable
    ) {
        private static StoreSnapshot empty(boolean readable) {
            return new StoreSnapshot(Set.of(), List.of(), List.of(), 0, readable);
        }
    }
}
