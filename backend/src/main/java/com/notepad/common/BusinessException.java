package com.notepad.common;

import lombok.Getter;

/**
 * 业务异常：code 与 HTTP 状态码保持一致
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
