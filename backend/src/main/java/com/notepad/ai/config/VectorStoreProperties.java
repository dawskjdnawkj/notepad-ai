package com.notepad.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 向量存储配置。
 *
 * 默认走进程内的 SimpleVectorStore；切到 pgvector 需要额外跑一个 PostgreSQL 实例，
 * 详见 {@link PgVectorConfiguration}。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "notepad.ai.vector-store")
public class VectorStoreProperties {

    /**
     * 存储类型。用 enum 接住，非法值在配置绑定阶段就报错，
     * 比启动后报「No qualifying bean of type VectorStore」清楚得多。
     */
    public enum Type {
        simple,
        pgvector
    }

    /** 默认 simple：交接文档要求保留 SimpleVectorStore 作为可回退方案。 */
    private Type type = Type.simple;

    /** 向量维度，必须与 spring.ai.dashscope.embedding.options.dimensions 保持一致。 */
    @Min(1)
    private int dimensions = 1024;

    @Valid
    private PgVector pgvector = new PgVector();

    @Data
    public static class PgVector {

        /** 表名/索引名会拼进 SQL，必须自己把关（PgVectorSchemaValidator 只校验它那一侧）。 */
        @Pattern(regexp = "^[A-Za-z0-9_]{1,64}$", message = "向量表名只允许字母、数字和下划线")
        private String tableName = "vector_store";

        @Pattern(regexp = "^[A-Za-z0-9_]{1,64}$", message = "schema 名只允许字母、数字和下划线")
        private String schemaName = "public";

        /**
         * NONE | IVFFLAT | HNSW。
         * 对比实验的基线用 NONE —— 与 SimpleVectorStore 一样是精确暴力检索，算法等价。
         *
         * 注意：索引名由 PgVectorStore 自己推导（表名为 vector_store 时是
         * spring_ai_vector_index），它的 builder 没有暴露索引名，所以这里不提供配置项。
         */
        private String indexType = "NONE";

        /** 是否自动建表。需要非超级用户部署时可关掉，改为手工执行 DDL。 */
        private boolean initializeSchema = true;

        private int maxDocumentBatchSize = 500;
    }
}
