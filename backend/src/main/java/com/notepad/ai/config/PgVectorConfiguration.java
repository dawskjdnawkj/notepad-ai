package com.notepad.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * pgvector 向量库的装配。只在 {@code notepad.ai.vector-store.type=pgvector} 时生效；
 * 默认的 simple 模式下这个类整体不加载，不引入任何 PostgreSQL 启动依赖。
 */
@Configuration
@ConditionalOnProperty(name = "notepad.ai.vector-store.type", havingValue = "pgvector")
public class PgVectorConfiguration {

    // ---------------- MySQL 主数据源 ----------------
    // 关键：Spring Boot 的池化数据源自动配置带 @ConditionalOnMissingBean(DataSource)。
    // 本项目一旦定义了任何一个 DataSource bean，MySQL 的自动配置就会整体退让。
    // 所以启用 pgvector 时必须把 MySQL 数据源显式补回来并标 @Primary，
    // 否则 MyBatis-Plus 的 @ConditionalOnSingleCandidate(DataSource) 会因为
    // 容器里存在两个候选而失效，整个持久层起不来。

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    // ---------------- PostgreSQL 向量数据源 ----------------

    @Bean
    @ConfigurationProperties("notepad.ai.vector-store.pgvector.datasource")
    public DataSourceProperties vectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("notepad.ai.vector-store.pgvector.datasource.hikari")
    public HikariDataSource vectorDataSource(
            @Qualifier("vectorDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 不能用自动配置的 JdbcTemplate —— 那一个绑在 @Primary 的 MySQL 上。
     * PgVectorStore 的每一条 SQL 都必须走 PostgreSQL。
     */
    @Bean
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }

    /**
     * 快照导入需要「先清空再灌入」整体原子化，不能用默认的（MySQL）事务管理器。
     */
    @Bean
    public PlatformTransactionManager vectorTransactionManager(
            @Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new DataSourceTransactionManager(vectorDataSource);
    }

    @Bean
    public TransactionTemplate vectorTransactionTemplate(
            @Qualifier("vectorTransactionManager") PlatformTransactionManager vectorTransactionManager) {
        return new TransactionTemplate(vectorTransactionManager);
    }

    @Bean
    public PgVectorStore pgVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            VectorStoreProperties properties) {
        VectorStoreProperties.PgVector pg = properties.getPgvector();
        return PgVectorStore.builder(vectorJdbcTemplate, embeddingModel)
                // 默认是 UUID：不改则 id 列建成 uuid 类型，
                // 本项目 note:8:53:chunk:0 这类确定性片段 ID 根本写不进去
                .idType(PgVectorStore.PgIdType.TEXT)
                // 默认是 false：不改则表永远不会创建，首次写入报 relation does not exist
                .initializeSchema(pg.isInitializeSchema())
                // 不给维度会回落到 1536，与 1024 维向量不匹配
                .dimensions(properties.getDimensions())
                // 与 SimpleVectorStore 的余弦相似度同量纲，检索阈值无需重标定
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // 默认是 HNSW：对比实验的基线要 NONE，也就是与 SimpleVectorStore 等价的精确暴力检索
                .indexType(PgVectorStore.PgIndexType.valueOf(pg.getIndexType()))
                .schemaName(pg.getSchemaName())
                .vectorTableName(pg.getTableName())
                // 显式写出来：改成 true 会在每次重启时 DROP TABLE 清空向量库
                .removeExistingVectorStoreTable(false)
                .maxDocumentBatchSize(pg.getMaxDocumentBatchSize())
                .build();
    }
}
