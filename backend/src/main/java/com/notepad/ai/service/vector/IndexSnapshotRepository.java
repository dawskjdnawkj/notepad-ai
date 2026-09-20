package com.notepad.ai.service.vector;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 向量库的「存储专有原语」。这是 SimpleVectorStore 与 pgvector 之间唯一的接缝。
 *
 * 刻意保持得很窄：只收敛 {@code NoteVectorService} 无法用标准 VectorStore 表达的算子。
 * 快照文件命名、临时文件 + 原子替换、13 项 JSON 校验、备份保留策略、四个 HTTP 接口
 * 与前端 UI 全部留在 NoteVectorService —— 它们操作的是「快照文件」这一与存储无关的
 * 交换格式，本来就不该跟着存储走。
 *
 * 约定：两种实现都必须支持同一份 JSON 快照格式
 * <pre>
 * { "&lt;documentId&gt;": { "text": ..., "embedding": [1024 个 float],
 *                       "id": "&lt;与外层键相同&gt;", "metadata": {...} } }
 * </pre>
 * 这既是 SimpleVectorStore.save 的既有格式，也是 validateStoreFile 校验的格式。
 */
public interface IndexSnapshotRepository {

    /**
     * 应用启动时恢复持久化状态。实现内部负责记录日志并吞掉可恢复的失败，
     * 不能让一次恢复失败导致整个应用起不来。
     */
    void restoreOnStartup();

    /**
     * 本地索引文件的路径；非文件型存储返回 empty。
     */
    Optional<Path> storeFile();

    /**
     * 索引数据目录。备份目录由它推导，所以两种存储都必须提供，
     * 且应当指向同一个位置，这样切换存储不会让已有备份"消失"。
     */
    Path dataDirectory();

    /**
     * 是否以「单个本地 JSON 文件」作为持久化载体。
     * pgvector 下为 false —— 写入在 SQL 层已经持久化，没有「落盘」这一步。
     */
    default boolean fileBacked() {
        return storeFile().isPresent();
    }

    /**
     * 把当前全库写成一份与 SimpleVectorStore.save 同格式的 JSON 快照到 target。
     * 调用方保证 target 是临时文件并负责原子替换，实现不需要保证原子性。
     */
    void exportSnapshot(Path target);

    /**
     * 用一份快照整体替换当前全库。输入必须已通过 validateStoreFile 校验。
     * 失败必须抛异常，且让当前库保持可用。
     */
    void importSnapshot(Path source);

    /**
     * 全库中属于该用户的片段 ID（顺序不保证）。
     * 读取失败抛 RuntimeException，由调用方决定如何降级。
     */
    List<String> documentIds(Long userId);
}
