package com.notepad.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.common.BusinessException;
import com.notepad.common.UserContext;
import com.notepad.entity.NoteImage;
import com.notepad.mapper.NoteImageMapper;
import com.notepad.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器：校验 Authorization: Bearer <token>，通过后把 userId 写入 UserContext
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final JwtCookieService jwtCookieService;
    private final NoteImageMapper noteImageMapper;
    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(AUTH_HEADER);
        String token = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length())
                : null;
        boolean uploadRequest = request.getRequestURI().startsWith("/uploads/");
        if (token == null && uploadRequest) {
            token = readCookie(request);
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        try {
            JwtService.TokenPayload payload = jwtService.parseToken(token);
            Long userId = payload.userId();
            // 版本号比对：登出 / 改密码后旧 token 立即失效；用户已删除（查不到）同样视为失效
            Integer currentVersion = userMapper.selectTokenVersion(userId);
            if (currentVersion == null || currentVersion != payload.tokenVersion()) {
                throw new BusinessException(401, "登录状态已失效，请重新登录");
            }
            UserContext.setUserId(userId);
            if (uploadRequest && !ownsImage(userId, request.getRequestURI())) {
                throw new BusinessException(404, "图片不存在");
            }
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                response.addHeader("Set-Cookie", jwtCookieService.create(token, request.isSecure()));
            }
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
    }

    private boolean ownsImage(Long userId, String requestUri) {
        return noteImageMapper.exists(new LambdaQueryWrapper<NoteImage>()
                .eq(NoteImage::getUserId, userId)
                .eq(NoteImage::getUrl, requestUri));
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (JwtCookieService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
