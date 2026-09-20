package com.notepad.ai.service.vector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 以单个本地 JSON 文件为载体的索引快照仓储（SimpleVectorStore）。
 *
 * 方法体是从 NoteVectorService 逐字搬过来的，行为未做任何改变。
 */
@Component
@ConditionalOnProperty(
        name = "notepad.ai.vector-store.type",
        havingValue = "simple",
        matchIfMissing = true)
public class SimpleFileIndexSnapshotRepository implements IndexSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SimpleFileIndexSnapshotRepository.class);
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private final SimpleVectorStore vectorStore;

    @Value("${notepad.ai.vector-store-file:./data/simple-vector-store.json}")
    private String vectorStoreFile;

    public SimpleFileIndexSnapshotRepository(SimpleVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void restoreOnStartup() {
        Path storePath = storePath();
        if (!Files.exists(storePath)) {
            log.info("未发现本地向量文件，将使用空向量库: {}", storePath);
            return;
        }
        try {
            vectorStore.load(storePath.toFile());
            log.info("已加载本地向量文件: {}", storePath);
        }
        catch (RuntimeException exception) {
            log.error("本地向量文件加载失败，请重新执行全量索引: {}", storePath, exception);
        }
    }

    @Override
    public Optional<Path> storeFile() {
        return Optional.of(storePath());
    }

    @Override
    public Path dataDirectory() {
        return storePath().getParent();
    }

    @Override
    public void exportSnapshot(Path target) {
        vectorStore.save(target.toFile());
    }

    @Override
    public void importSnapshot(Path source) {
        vectorStore.load(source.toFile());
    }

    /**
     * 只做「按用户前缀取片段 ID」这一件事，不判断 ID 是否合法 ——
     * ID 的格式（note:userId:noteId:chunk:n）是服务层约定，由调用方校验。
     */
    @Override
    public List<String> documentIds(Long userId) {
        Path path = storePath();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }

        String prefix = "note:" + userId + ":";
        List<String> documentIds = new ArrayList<>();
        try (JsonParser parser = JSON_FACTORY.createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("向量索引文件结构不正确: " + path);
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String documentId = parser.currentName();
                parser.nextToken();
                if (documentId.startsWith(prefix)) {
                    documentIds.add(documentId);
                }
                parser.skipChildren();
            }
            return documentIds;
        }
        catch (IOException exception) {
            throw new IllegalStateException("读取向量索引文件失败: " + path, exception);
        }
    }

    /**
     * IntelliJ 可能以项目根目录作为工作目录；此时仍应使用 backend/data，
     * 避免因启动方式不同而创建第二份空向量库。
     */
    private Path storePath() {
        return IndexDataDirectory.resolve(vectorStoreFile);
    }
}
