package com.notepad.ai.event;

import com.notepad.ai.service.NoteVectorService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class NoteVectorSyncListener {

    private static final Logger log = LoggerFactory.getLogger(NoteVectorSyncListener.class);
    private final NoteVectorService noteVectorService;
    private final Map<String, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "note-vector-sync");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${notepad.ai.sync-delay:5s}")
    private Duration syncDelay;

    public NoteVectorSyncListener(NoteVectorService noteVectorService) {
        this.noteVectorService = noteVectorService;
    }

    /**
     * 数据库事务成功提交后再同步向量，避免数据库回滚时留下脏索引。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public synchronized void synchronize(NoteVectorSyncEvent event) {
        String taskKey = event.userId() + ":" + event.noteId();
        ScheduledFuture<?> pendingTask = pendingTasks.remove(taskKey);
        if (pendingTask != null) {
            pendingTask.cancel(false);
        }

        if (event.operation() == NoteVectorSyncEvent.Operation.DELETE) {
            synchronizeNow(event);
            return;
        }

        // 前端每次停止输入 700ms 就会自动保存；等待一段安静期后再生成向量，避免频繁调用百炼。
        ScheduledFuture<?>[] scheduledTask = new ScheduledFuture<?>[1];
        scheduledTask[0] = scheduler.schedule(() -> {
            try {
                synchronizeNow(event);
            }
            finally {
                pendingTasks.remove(taskKey, scheduledTask[0]);
            }
        }, syncDelay.toMillis(), TimeUnit.MILLISECONDS);
        pendingTasks.put(taskKey, scheduledTask[0]);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    private void synchronizeNow(NoteVectorSyncEvent event) {
        try {
            if (event.operation() == NoteVectorSyncEvent.Operation.UPSERT) {
                noteVectorService.indexNote(event.userId(), event.noteId());
            }
            else {
                noteVectorService.deleteNote(event.userId(), event.noteId());
            }
        }
        catch (RuntimeException exception) {
            // 数据库已经提交，向量失败不能反过来让笔记接口报错。
            log.error("同步笔记向量失败: userId={}, noteId={}, operation={}",
                    event.userId(), event.noteId(), event.operation(), exception);
        }
    }
}
