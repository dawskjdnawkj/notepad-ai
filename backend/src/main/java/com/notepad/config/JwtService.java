package com.notepad.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（HS256）
 * <p>
 * 载荷除 sub/iat/exp 外还带一个 token 版本号（claim 名 tv）。
 * 服务端 user.token_version 自增后，所有携带旧版本号的 token 立即失效，
 * 用于登出、修改密码、重置密码后强制下线。
 */
@Component
public class JwtService {

    /** 版本号 claim 名。升级前签发的存量 token 没有这个 claim，解析时按 0 处理 */
    public static final String CLAIM_TOKEN_VERSION = "tv";

    private static final int DEFAULT_TOKEN_VERSION = 0;

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getExpireDays() * 24 * 60 * 60 * 1000L;
    }

    /**
     * 解析结果：用户 ID + token 版本号
     */
    public record TokenPayload(Long userId, int tokenVersion) {
    }

    public String generateToken(Long userId, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public TokenPayload parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new TokenPayload(Long.valueOf(claims.getSubject()), readTokenVersion(claims));
    }

    /**
     * jjwt 0.12.x 读自定义 claim：claims.get(name) 返回 Object，底层 Jackson 对较小的数字
     * 反序列化成 Integer，直接按 Integer.class 取会在类型不匹配时抛 RequiredTypeException，
     * 因此统一按 Number 取；存量 token 没有该 claim 时返回 0。
     */
    private int readTokenVersion(Claims claims) {
        Object raw = claims.get(CLAIM_TOKEN_VERSION);
        return raw instanceof Number number ? number.intValue() : DEFAULT_TOKEN_VERSION;
    }
}
