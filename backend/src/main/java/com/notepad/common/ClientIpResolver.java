package com.notepad.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析真实客户端 IP，用于登录失败限流
 * <p>
 * 生产部署在 Nginx 之后（见 deploy/nginx/conf.d/notepad.conf）：Nginx 用 $remote_addr
 * 覆盖写 X-Real-IP，客户端伪造不了，因此优先取它；X-Forwarded-For 用的是
 * $proxy_add_x_forwarded_for，会把客户端传来的值接在最前面，第一段可伪造，
 * 只能取最后一段；本地直连 8080 时两个头都不存在，回退到 remoteAddr。
 */
public final class ClientIpResolver {

    private static final String X_REAL_IP = "X-Real-IP";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    /** 头部由外部输入，截断防止超长 value 撑大限流 Map 的 key */
    private static final int MAX_LENGTH = 64;

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String realIp = trimToNull(request.getHeader(X_REAL_IP));
        if (realIp != null && !UNKNOWN.equalsIgnoreCase(realIp)) {
            return truncate(realIp);
        }
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = trimToNull(parts[i]);
                if (candidate != null && !UNKNOWN.equalsIgnoreCase(candidate)) {
                    return truncate(candidate);
                }
            }
        }
        String remote = trimToNull(request.getRemoteAddr());
        return remote == null ? UNKNOWN : truncate(remote);
    }

    private static String truncate(String ip) {
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
