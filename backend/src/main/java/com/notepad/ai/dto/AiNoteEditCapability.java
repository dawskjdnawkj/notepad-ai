package com.notepad.ai.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * AI 编辑笔记的四种能力。
 *
 * 走线上的值是全小写的短名（summarize / rewrite / continue / todos），
 * 与项目里 scopeType、matchMode 这类枚举语义字段的风格一致。
 * Java 常量名不能是 continue 关键字，所以另存一份线上值。
 */
public enum AiNoteEditCapability {

    SUMMARIZE("summarize"),
    REWRITE("rewrite"),
    CONTINUE("continue"),
    TODOS("todos");

    private final String value;

    AiNoteEditCapability(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AiNoteEditCapability from(String value) {
        if (value != null) {
            for (AiNoteEditCapability candidate : values()) {
                if (candidate.value.equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("不支持的 AI 编辑能力：" + value);
    }
}
