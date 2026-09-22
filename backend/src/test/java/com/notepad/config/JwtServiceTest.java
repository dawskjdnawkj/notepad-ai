package com.notepad.config;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 生成与解析。
 * <p>
 * token 里除 sub/iat/exp 外还带一个版本号 claim，服务端靠它做吊销。
 * 这里既测往返，也测**升级前签发的存量 token**（没有该 claim）能否照常解析 ——
 * 这决定了上线时会不会把所有在线用户踢下线。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-must-be-at-least-32-bytes-long";
    private static final long USER_ID = 42L;

    private JwtService newService() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireDays(7);
        return new JwtService(properties);
    }

    @Test
    @DisplayName("生成的 token 能解析回同一个 userId 和版本号")
    void roundTripsUserIdAndVersion() {
        JwtService service = newService();

        String token = service.generateToken(USER_ID, 7);
        JwtService.TokenPayload payload = service.parseToken(token);

        assertThat(payload.userId()).isEqualTo(USER_ID);
        assertThat(payload.tokenVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("版本号 0 也要能正确往返（不能用 0 当「没有该 claim」的哨兵）")
    void roundTripsZeroVersion() {
        JwtService service = newService();

        JwtService.TokenPayload payload = service.parseToken(service.generateToken(USER_ID, 0));

        assertThat(payload.tokenVersion()).isZero();
    }

    @Test
    @DisplayName("存量 token 没有版本号 claim 时按 0 处理：升级不会强制所有人重新登录")
    void legacyTokenWithoutVersionClaimParsesAsZero() {
        JwtService service = newService();

        // 手工签一个「升级前」格式的 token：只有 sub/iat/exp
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        String legacyToken = Jwts.builder()
                .subject(String.valueOf(USER_ID))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(key)
                .compact();

        JwtService.TokenPayload payload = service.parseToken(legacyToken);

        assertThat(payload.userId()).isEqualTo(USER_ID);
        assertThat(payload.tokenVersion()).isZero();
    }

    @Test
    @DisplayName("签名不匹配的 token 解析失败，不能放行")
    void rejectsTokenSignedWithAnotherKey() {
        JwtService service = newService();
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-also-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject(String.valueOf(USER_ID))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> service.parseToken(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("过期 token 解析失败")
    void rejectsExpiredToken() {
        JwtService service = newService();
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject(String.valueOf(USER_ID))
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> service.parseToken(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("篡改过的 token 解析失败")
    void rejectsTamperedToken() {
        JwtService service = newService();
        String token = service.generateToken(USER_ID, 0);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> service.parseToken(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
