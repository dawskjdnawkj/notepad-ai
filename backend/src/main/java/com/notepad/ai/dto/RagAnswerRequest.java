package com.notepad.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 基于当前用户已索引笔记进行问答的请求。
 */
public record RagAnswerRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最多 500 个字符")
        String question,

        @Valid
        @Size(max = 10, message = "最多携带 10 条历史消息")
        List<ConversationMessage> history,

        @Positive(message = "笔记 ID 必须为正数")
        Long noteId,

        @Positive(message = "笔记本 ID 必须为正数")
        Long notebookId
) {

    /**
     * history 只用于理解追问中的指代，不作为笔记事实来源。
     */
    public record ConversationMessage(
            @NotBlank(message = "历史消息角色不能为空")
            @Pattern(regexp = "user|assistant", message = "历史消息角色只能是 user 或 assistant")
            String role,

            @NotBlank(message = "历史消息内容不能为空")
            @Size(max = 6000, message = "单条历史消息最多 6000 个字符")
            String content
    ) {
    }
}
