package com.notepad.ai.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.ai.dto.AiNoteEditApplyRequest;
import com.notepad.ai.dto.AiNoteEditApplyResponse;
import com.notepad.ai.dto.AiNoteEditCapability;
import com.notepad.ai.dto.AiNoteRevisionResponse;
import com.notepad.common.BusinessException;
import com.notepad.dto.NoteUpdateRequest;
import com.notepad.entity.AiNoteRevision;
import com.notepad.entity.Note;
import com.notepad.mapper.AiNoteRevisionMapper;
import com.notepad.mapper.NoteMapper;
import com.notepad.service.NoteService;
import com.notepad.vo.NoteDetailVO;
import com.notepad.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 编辑笔记：摘要 / 改写 / 续写 / 提取待办。
 *
 * 流程固定为「读取笔记 → 生成预览 → 用户确认 → 写回 → 重新同步向量索引」，
 * 模型不直接写库：{@link #streamPreview} 只产出文本，写回必须由
 * {@link #apply} 在客户端回传内容指纹、校验通过之后才发生。
 *
 * 写回一律走 {@link NoteService#update}，因此标签、图片绑定和向量重新同步
 * 都沿用既有链路，本类不直接写 notes 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNoteEditService {

    /** 每篇笔记最多保留的原文快照数量，超出后按最旧优先清理。 */
    private static final int MAX_REVISIONS_PER_NOTE = 10;
    /** 送给模型的正文上限，防止超长笔记把上下文撑爆。 */
    private static final int MAX_INPUT_CHARS = 20_000;
    private static final double TEMPERATURE = 0.3;

    private static final String BASE_CONSTRAINTS = """
            - 只依据给定的笔记正文，不要编造笔记里没有的内容
            - 直接输出结果正文，不要写"好的""以下是"这类开场白或解释
            - 只输出普通中文和标点，不要输出 HTML 标签、HTML 实体或 Markdown 标题符号
            """;

    private static final String SUMMARIZE_PROMPT = """
            你是云笔记系统中的写作助手。请为下面这篇笔记写一段简洁的摘要。
            """ + BASE_CONSTRAINTS + """
            - 摘要控制在 3 到 5 句话，抓住核心信息
            """;

    private static final String REWRITE_PROMPT = """
            你是云笔记系统中的写作助手。请在保持原意的前提下，把下面这篇笔记的正文
            改写得更通顺、更有条理。
            """ + BASE_CONSTRAINTS + """
            - 不增加原文没有的信息，也不删减关键内容
            - 输出改写后的完整正文
            """;

    private static final String CONTINUE_PROMPT = """
            你是云笔记系统中的写作助手。请顺着下面这篇笔记的内容往下续写。
            """ + BASE_CONSTRAINTS + """
            - 续写要与笔记主题连贯，不要另起炉灶
            - 只输出续写的部分，不要重复原文
            """;

    private static final String TODOS_PROMPT = """
            你是云笔记系统中的写作助手。请从下面这篇笔记中提取出待办事项。
            """ + BASE_CONSTRAINTS + """
            - 只提取笔记里确实提到的待办，不要自己发明
            - 每条一行，以"- "开头，一行一件事
            - 如果笔记里没有明确的待办，输出"未发现明确的待办事项"
            """;

    private final ChatClient chatClient;
    private final NoteService noteService;
    private final NoteMapper noteMapper;
    private final AiNoteRevisionMapper revisionMapper;

    @Value("${notepad.ai.http.stream-timeout:150s}")
    private Duration streamTimeout;

    /**
     * 一次预览的产物：正文指纹 + 模型输出的文本流。
     * 指纹随 SSE 的 done 事件发给前端，确认写回时回传比对。
     */
    public record StreamPreview(String contentHash, Flux<String> content) {
    }

    /**
     * 读取笔记并启动一次流式生成。不做任何写入。
     */
    public StreamPreview streamPreview(Long userId, Long noteId, AiNoteEditCapability capability) {
        Note note = requireOwnedNote(userId, noteId);
        String plainText = note.getContentText() == null ? "" : note.getContentText();
        if (plainText.isBlank()) {
            throw new BusinessException(400, "这篇笔记还没有正文，无法进行 AI 编辑");
        }
        if (plainText.length() > MAX_INPUT_CHARS) {
            log.warn("event=ai.note.edit.input_truncated userId={} noteId={} chars={} limit={}",
                    userId, noteId, plainText.length(), MAX_INPUT_CHARS);
            plainText = plainText.substring(0, MAX_INPUT_CHARS);
        }

        String contentHash = sha256(note.getContent());
        String title = note.getTitle() == null ? "无标题" : note.getTitle();
        String userPrompt = "笔记标题：" + title + "\n\n笔记正文：\n" + plainText;
        String systemPrompt = systemPrompt(capability);

        // 只在首个 token 之前重试：有输出之后重试会造成重复内容。
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<String> content = Flux.defer(() -> chatClient.prompt()
                        .system(systemPrompt)
                        .options(DashScopeChatOptions.builder().withTemperature(TEMPERATURE).build())
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(text -> {
                            if (text != null && !text.isEmpty()) {
                                emitted.set(true);
                            }
                        }))
                // 响应式流不会使用 RestClient 的读取超时，需要单独约束。
                .timeout(streamTimeout)
                .retryWhen(Retry.backoff(1, Duration.ofMillis(800))
                        .filter(error -> !emitted.get() && isRetryableConnectionError(error))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));

        log.info("event=ai.note.edit.preview.started userId={} noteId={} capability={}",
                userId, noteId, capability.value());
        return new StreamPreview(contentHash, content);
    }

    /**
     * 用户确认后写回。
     *
     * 先校验内容指纹：预览期间笔记被改过就直接 409，绝不覆盖用户的新内容。
     */
    @Transactional(rollbackFor = Exception.class)
    public AiNoteEditApplyResponse apply(Long userId, Long noteId, AiNoteEditApplyRequest request) {
        Note note = requireOwnedNote(userId, noteId);
        if (!sha256(note.getContent()).equals(request.contentHash())) {
            throw new BusinessException(409, "笔记在生成预览期间已被修改，请重新生成后再应用");
        }

        String aiText = request.text() == null ? "" : request.text().strip();
        if (aiText.isEmpty()) {
            throw new BusinessException(400, "AI 结果为空，无法写回");
        }

        NoteDetailVO current = noteService.detail(userId, noteId);
        String newContent = compose(current.getContent(), aiText, request.capability());

        AiNoteRevision revision = saveRevision(
                userId, noteId, request.capability(), current.getTitle(), current.getContent(), newContent);

        NoteDetailVO updated = noteService.update(userId, noteId, buildUpdateRequest(current, newContent));
        log.info("event=ai.note.edit.applied userId={} noteId={} capability={} revisionId={}",
                userId, noteId, request.capability().value(), revision.getId());
        return new AiNoteEditApplyResponse(revision.getId(), updated);
    }

    public List<AiNoteRevisionResponse> listRevisions(Long userId, Long noteId) {
        requireOwnedNote(userId, noteId);
        return revisionMapper.selectList(new LambdaQueryWrapper<AiNoteRevision>()
                        .eq(AiNoteRevision::getUserId, userId)
                        .eq(AiNoteRevision::getNoteId, noteId)
                        .orderByDesc(AiNoteRevision::getId))
                .stream()
                .map(this::toRevisionResponse)
                .toList();
    }

    /**
     * 把笔记正文回退到某一次 AI 编辑之前。
     * 允许重复回退：用户可能回退后又做了别的编辑，再想回到同一个版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public NoteDetailVO restore(Long userId, Long noteId, Long revisionId) {
        requireOwnedNote(userId, noteId);
        AiNoteRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<AiNoteRevision>()
                .eq(AiNoteRevision::getId, revisionId)
                .eq(AiNoteRevision::getUserId, userId)
                .eq(AiNoteRevision::getNoteId, noteId));
        if (revision == null) {
            throw new BusinessException(404, "没有找到可回退的版本");
        }

        NoteDetailVO current = noteService.detail(userId, noteId);
        NoteDetailVO updated = noteService.update(
                userId, noteId, buildUpdateRequest(current, revision.getContentBefore()));

        revision.setRestored(1);
        revision.setRestoreTime(LocalDateTime.now());
        revisionMapper.updateById(revision);
        log.info("event=ai.note.edit.restored userId={} noteId={} revisionId={}",
                userId, noteId, revisionId);
        return updated;
    }

    /**
     * 构造写回请求。
     *
     * 关键：必须带上笔记当前的 title 与 tagIds —— NoteService.update 是全量覆盖，
     * saveTags 会先删光该笔记的全部标签行再按传入值重建，不带就是一次编辑清空用户标签。
     */
    private NoteUpdateRequest buildUpdateRequest(NoteDetailVO current, String newContent) {
        NoteUpdateRequest request = new NoteUpdateRequest();
        request.setTitle(current.getTitle());
        request.setContent(newContent);
        request.setTagIds(current.getTags() == null
                ? List.of()
                : current.getTags().stream().map(TagVO::getId).filter(Objects::nonNull).toList());
        return request;
    }

    /**
     * 按能力把 AI 文本合进原正文。
     * 摘要插到最前、改写整体替换、续写与待办追加到末尾。
     */
    private String compose(String originalContent, String aiText, AiNoteEditCapability capability) {
        String base = originalContent == null ? "" : originalContent;
        String block = toHtmlBlock(aiText);
        return switch (capability) {
            case SUMMARIZE -> block + base;
            case REWRITE -> block;
            case CONTINUE, TODOS -> base + block;
        };
    }

    /**
     * 把模型输出的纯文本转成可存进 notes.content 的 HTML。
     *
     * 必须先做 HTML 转义再包标签：正文最终会被富文本编辑器渲染，
     * 模型输出里若带 &lt;script&gt; 之类的内容，不转义就会变成可执行标记。
     */
    private String toHtmlBlock(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder html = new StringBuilder(normalized.length() + 64);
        for (String line : normalized.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            html.append("<p>").append(escapeHtml(trimmed)).append("</p>");
        }
        return html.toString();
    }

    private String escapeHtml(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private AiNoteRevision saveRevision(Long userId, Long noteId, AiNoteEditCapability capability,
                                        String titleBefore, String contentBefore, String contentAfter) {
        AiNoteRevision revision = new AiNoteRevision();
        revision.setUserId(userId);
        revision.setNoteId(noteId);
        revision.setCapability(capability.value());
        revision.setTitleBefore(titleBefore);
        revision.setContentBefore(contentBefore == null ? "" : contentBefore);
        revision.setContentAfter(contentAfter);
        revision.setRestored(0);
        revision.setCreateTime(LocalDateTime.now());
        revisionMapper.insert(revision);
        pruneRevisions(userId, noteId);
        return revision;
    }

    /** 每篇笔记只保留最近若干份快照，避免无限增长。 */
    private void pruneRevisions(Long userId, Long noteId) {
        List<AiNoteRevision> all = revisionMapper.selectList(new LambdaQueryWrapper<AiNoteRevision>()
                .select(AiNoteRevision::getId)
                .eq(AiNoteRevision::getUserId, userId)
                .eq(AiNoteRevision::getNoteId, noteId)
                .orderByDesc(AiNoteRevision::getId));
        if (all.size() <= MAX_REVISIONS_PER_NOTE) {
            return;
        }
        List<Long> expired = new ArrayList<>();
        for (int i = MAX_REVISIONS_PER_NOTE; i < all.size(); i++) {
            expired.add(all.get(i).getId());
        }
        revisionMapper.deleteBatchIds(expired);
        log.info("event=ai.note.edit.revision.pruned userId={} noteId={} removedCount={}",
                userId, noteId, expired.size());
    }

    private AiNoteRevisionResponse toRevisionResponse(AiNoteRevision revision) {
        return new AiNoteRevisionResponse(
                revision.getId(),
                revision.getCapability(),
                revision.getCreateTime(),
                Integer.valueOf(1).equals(revision.getRestored()),
                revision.getRestoreTime());
    }

    private Note requireOwnedNote(Long userId, Long noteId) {
        Note note = noteMapper.selectById(noteId);
        // 不属于自己的笔记一律按不存在处理，不泄露存在性
        if (note == null || !userId.equals(note.getUserId())) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    private String systemPrompt(AiNoteEditCapability capability) {
        return switch (capability) {
            case SUMMARIZE -> SUMMARIZE_PROMPT;
            case REWRITE -> REWRITE_PROMPT;
            case CONTINUE -> CONTINUE_PROMPT;
            case TODOS -> TODOS_PROMPT;
        };
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private boolean isRetryableConnectionError(Throwable throwable) {
        // 超时通常继续重试也来不及，直接交给上层返回明确提示。
        if (hasCause(throwable, SocketTimeoutException.class)
                || hasCause(throwable, HttpTimeoutException.class)
                || hasCause(throwable, java.util.concurrent.TimeoutException.class)) {
            return false;
        }
        return hasCause(throwable, IOException.class);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
