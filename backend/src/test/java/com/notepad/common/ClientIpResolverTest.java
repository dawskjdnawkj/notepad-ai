package com.notepad.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户端 IP 解析。
 * <p>
 * 这段逻辑决定了限流的 key，取错的话「按 IP 兜底」就形同虚设：
 * X-Forwarded-For 用的是 $proxy_add_x_forwarded_for，客户端传来的值会被接在最前面，
 * 所以只有最后一段是 Nginx 追加的可信地址。
 */
class ClientIpResolverTest {

    private HttpServletRequest request(String realIp, String forwardedFor, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    @DisplayName("优先取 X-Real-IP：它由 Nginx 用 $remote_addr 覆盖写，客户端伪造不了")
    void prefersRealIp() {
        assertThat(ClientIpResolver.resolve(request("1.2.3.4", null, "10.0.0.1")))
                .isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("X-Forwarded-For 取最后一段，不能取第一段（第一段是客户端自己塞的）")
    void takesLastForwardedSegment() {
        assertThat(ClientIpResolver.resolve(request(null, "6.6.6.6, 1.2.3.4", "10.0.0.1")))
                .isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("X-Real-IP 缺失时回退到 remoteAddr")
    void fallsBackToRemoteAddr() {
        assertThat(ClientIpResolver.resolve(request(null, null, "10.0.0.1")))
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("跳过 unknown 占位值，继续往后找")
    void skipsUnknownPlaceholders() {
        assertThat(ClientIpResolver.resolve(request("unknown", "unknown, 1.2.3.4", "10.0.0.1")))
                .isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("全都取不到时返回 unknown，不能返回 null（否则会变成限流 map 里的 null key）")
    void neverReturnsNull() {
        assertThat(ClientIpResolver.resolve(request(null, null, null)))
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("超长头部要截断：头部是外部输入，不能让它撑大限流 map 的 key")
    void truncatesOverlongHeader() {
        String huge = "a".repeat(500);
        assertThat(ClientIpResolver.resolve(request(huge, null, "10.0.0.1")))
                .hasSize(64);
    }

    @Test
    @DisplayName("空白字符要去掉再判断")
    void trimsBlankValues() {
        assertThat(ClientIpResolver.resolve(request("   ", " 1.2.3.4 ", "10.0.0.1")))
                .isEqualTo("1.2.3.4");
    }
}
