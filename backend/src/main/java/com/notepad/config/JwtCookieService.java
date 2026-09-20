package com.notepad.config;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 为浏览器原生图片请求同步 JWT。Cookie 仅用于受保护的 /uploads/**，
 * 普通 API 仍要求 Authorization 请求头，避免扩大 Cookie 鉴权面。
 */
@Component
public class JwtCookieService {

    public static final String COOKIE_NAME = "notepad_access";

    private final JwtProperties properties;

    public JwtCookieService(JwtProperties properties) {
        this.properties = properties;
    }

    public String create(String token, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(properties.getExpireDays()))
                .build()
                .toString();
    }

    public String clear(boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }
}
