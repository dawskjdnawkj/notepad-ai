package com.notepad.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * JWT 拦截器注册：除注册/登录外，所有 /api/** 接口都要求携带有效 token
 */
@Configuration
@RequiredArgsConstructor
public class JwtConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**", "/uploads/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register", "/api/auth/send-code",
                        "/api/auth/password/reset-code", "/api/auth/password/reset");
    }
}
