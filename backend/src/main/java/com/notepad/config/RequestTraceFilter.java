package com.notepad.config;

import com.notepad.common.RequestTraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为所有 API 请求建立可回查的 requestId，并记录统一的访问耗时日志。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = RequestTraceContext.normalizeOrCreate(
                request.getHeader(RequestTraceContext.REQUEST_ID_HEADER));
        MDC.put(RequestTraceContext.MDC_KEY, requestId);
        response.setHeader(RequestTraceContext.REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
            log.info(
                    "event=http.request.completed method={} path={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            MDC.remove(RequestTraceContext.MDC_KEY);
        }
    }
}
