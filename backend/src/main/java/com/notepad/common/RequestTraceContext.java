package com.notepad.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求追踪上下文：为 HTTP 响应、日志和 SSE 错误复用同一个 requestId。
 */
public final class RequestTraceContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private RequestTraceContext() {
    }

    public static String normalizeOrCreate(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String currentId() {
        String requestId = MDC.get(MDC_KEY);
        return requestId == null || requestId.isBlank()
                ? normalizeOrCreate(null)
                : requestId;
    }
}
