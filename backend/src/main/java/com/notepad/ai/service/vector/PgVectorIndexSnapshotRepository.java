package com.notepad.ai.service.vector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notepad.ai.config.VectorStoreProperties;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pgvector 索引快照仓储。
 *
 * 把 pgvector 表桥接成与 {@code SimpleVectorStore.save} 完全相同格式的 JSON 快照，
 * 于是第一阶段做的全部东西 —— 13 项校验、原子写、保留策略、四个接口、前端 UI ——
 * 都能原样复用，切换存储不需要改动上层任何一行。
 */
@Component
@ConditionalOnProperty(name = "notepad.ai.vector-store.type", havingValue = "pgvector")
public class PgVectorIndexSnapshotRepository implements IndexSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(PgVectorIndexSnapshotRepository.class);
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final String EXPORT_SQL =
            "SELECT id, content, metadata::text AS metadata, embedding::text AS embedding FROM %s ORDER BY id";
    private static final String COUNT_SQL = "SELECT count(*) FROM %s";
    private static final String TRUNCATE_SQL = "TRUNCATE TABLE %s";
    private static final String ANALYZE_SQL = "ANALYZE %s";
    /** 与 Spring AI 自己的写入语句一致，保证恢复出来的行和 add() 写出的行完全同构。 */
    private static final String UPSERT_SQL =
            "INSERT INTO %s (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?) "
                    + "ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?";
    private static final String USER_IDS_SQL = "SELECT id FROM %s WHERE id LIKE ? ORDER BY id";

    private final JdbcTemplate vectorJdbcTemplate;
    private final TransactionTemplate vectorTransactionTemplate;
    private final ObjectMapper objectMapper;
    private final VectorStoreProperties properties;

    @Value("${notepad.ai.vector-store-file:./data/simple-vector-store.json}")
    private String vectorStoreFile;

    public PgVectorIndexSnapshotRepository(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            @Qualifier("vectorTransactionTemplate") TransactionTemplate vectorTransactionTemplate,
            ObjectMapper objectMapper,
            VectorStoreProperties properties,
            // 只为保证初始化顺序：PgVectorStore 是 InitializingBean，
            // 它的 afterPropertiesSet 负责建表，必须先于下面任何一条 SQL 完成。
            VectorStore vectorStore) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.vectorTransactionTemplate = vectorTransactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void restoreOnStartup() {
        // pgvector 的数据本来就在库里，没有「载入」这一步，只做一次连通性确认。
        try {
            Integer rows = vectorJdbcTemplate.queryForObject(
                    String.format(COUNT_SQL, tableName()), Integer.class);
            log.info("已连接 pgvector 向量库: table={} rows={}", tableName(), rows);
        }
        catch (RuntimeException exception) {
            log.error("pgvector 向量库不可用，请检查 PostgreSQL 连接与 vector 扩展", exception);
        }
    }

    @Override
    public Optional<Path> storeFile() {
        return Optional.empty();
    }

    @Override
    public Path dataDirectory() {
        // 与 simple 模式解析同一份配置，保证切换存储后备份目录不变、已有备份不会「消失」。
        Path storePath = IndexDataDirectory.resolve(vectorStoreFile);
        Path parent = storePath.getParent();
        return parent == null ? storePath : parent;
    }

    @Override
    public void exportSnapshot(Path target) {
        String sql = String.format(EXPORT_SQL, tableName());
        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             JsonGenerator generator = objectMapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            vectorJdbcTemplate.query(sql, resultSet -> {
                try {
                    String id = resultSet.getString("id");
                    generator.writeFieldName(id);
                    generator.writeStartObject();
                    // 字段名必须与 SimpleVectorStore 的输出逐字一致，
                    // 否则 validateStoreFile 里的 case "text" / "embedding" / "id" 读不到。
                    generator.writeStringField("text", resultSet.getString("content"));
                    generator.writeArrayFieldStart("embedding");
                    for (float value : parseVectorText(resultSet.getString("embedding"))) {
                        generator.writeNumber(value);
                    }
                    generator.writeEndArray();
                    generator.writeStringField("id", id);
                    generator.writeObjectField("metadata", readMetadata(resultSet.getString("metadata")));
                    generator.writeEndObject();
                }
                catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            generator.writeEndObject();
        }
        catch (IOException exception) {
            throw new IllegalStateException("导出向量快照失败: " + target, exception);
        }
    }

    @Override
    public void importSnapshot(Path source) {
        // TRUNCATE 在 PostgreSQL 里是事务性的：放进事务后，「先清空再灌入」失败会整体回滚，
        // 当前库保持原样 —— 与 SimpleVectorStore.load「解析成功才替换引用」的语义对齐。
        vectorTransactionTemplate.executeWithoutResult(status -> {
            vectorJdbcTemplate.execute(String.format(TRUNCATE_SQL, tableName()));
            int batchSize = Math.max(1, properties.getPgvector().getMaxDocumentBatchSize());
            List<Object[]> batch = new ArrayList<>(batchSize);
            try (JsonParser parser = JSON_FACTORY.createParser(source.toFile())) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IllegalStateException("快照结构不正确: " + source);
                }
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String documentId = parser.currentName();
                    parser.nextToken();
                    batch.add(toUpsertArgs(documentId, parser));
                    if (batch.size() >= batchSize) {
                        flush(batch);
                    }
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                }
            }
            catch (IOException exception) {
                throw new IllegalStateException("读取快照失败: " + source, exception);
            }
            vectorJdbcTemplate.execute(String.format(ANALYZE_SQL, tableName()));
        });
    }

    @Override
    public List<String> documentIds(Long userId) {
        return vectorJdbcTemplate.queryForList(
                String.format(USER_IDS_SQL, tableName()),
                String.class,
                "note:" + userId + ":%");
    }

    private void flush(List<Object[]> batch) {
        vectorJdbcTemplate.batchUpdate(String.format(UPSERT_SQL, tableName()), batch);
        batch.clear();
    }

    /**
     * 解析快照里的单条片段，拼成 UPSERT 的参数。
     * upsert 语句有两组同样的参数（INSERT 一组、DO UPDATE 一组），所以每个值要放两次。
     */
    private Object[] toUpsertArgs(String documentId, JsonParser parser) throws IOException {
        String text = null;
        Map<String, Object> metadata = null;
        float[] embedding = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "text" -> text = parser.getText();
                case "metadata" -> metadata = objectMapper.readValue(parser, Map.class);
                case "embedding" -> {
                    List<Float> values = new ArrayList<>(properties.getDimensions());
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        values.add(parser.getFloatValue());
                    }
                    embedding = new float[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        embedding[i] = values.get(i);
                    }
                }
                default -> parser.skipChildren();
            }
        }

        String json = objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        // 每行都新建 PGvector：它是可变的，不能跨行复用同一个实例。
        return new Object[]{
                documentId, text, json, new PGvector(embedding),
                text, json, new PGvector(embedding)};
    }

    /**
     * pgvector 的文本形式是 {@code [v1,v2,...]}。
     * 用 embedding::text 读出再手工解析，免去注册 JDBC 向量类型的麻烦。
     */
    private float[] parseVectorText(String text) {
        String body = text == null ? "" : text.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    private Map<String, Object> readMetadata(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, Map.class);
    }

    private String tableName() {
        VectorStoreProperties.PgVector pg = properties.getPgvector();
        return pg.getSchemaName() + "." + pg.getTableName();
    }
}
