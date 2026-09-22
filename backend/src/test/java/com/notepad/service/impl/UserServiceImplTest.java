package com.notepad.service.impl;

import com.notepad.common.BusinessException;
import com.notepad.config.JwtService;
import com.notepad.dto.LoginRequest;
import com.notepad.entity.User;
import com.notepad.mapper.NotebookMapper;
import com.notepad.mapper.UserMapper;
import com.notepad.service.LoginAttemptLimiter;
import com.notepad.service.MailService;
import com.notepad.service.VerificationCodeService;
import com.notepad.vo.LoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录编排。
 * <p>
 * 重点不是「能不能登进去」，而是几步的**顺序与分支**：
 * 计额度必须在验密之前（否则并发突发能突破阈值），
 * 归还额度只发生在密码确实校验通过之后（否则失败次数会被成功登录抹掉）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String USERNAME = "alice";
    private static final String CLIENT_IP = "1.2.3.4";

    @Mock
    private UserMapper userMapper;
    @Mock
    private NotebookMapper notebookMapper;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private MailService mailService;
    @Mock
    private VerificationCodeService verificationCodeService;
    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks
    private UserServiceImpl userService;

    private LoginRequest request(String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(password);
        return request;
    }

    private User existingUser(int status, int tokenVersion) {
        User user = new User();
        user.setId(7L);
        user.setUsername(USERNAME);
        user.setPassword("$2a$10$hashed");
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    @Test
    @DisplayName("先占额度再验密：顺序反了并发突发就能突破阈值")
    void acquiresBudgetBeforeVerifyingPassword() {
        User user = existingUser(1, 3);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(jwtService.generateToken(anyLong(), anyInt())).thenReturn("token");

        userService.login(request("correct"), CLIENT_IP);

        InOrder order = inOrder(loginAttemptLimiter, passwordEncoder);
        order.verify(loginAttemptLimiter).tryAcquire(USERNAME, CLIENT_IP);
        order.verify(passwordEncoder).matches(any(), any());
    }

    @Test
    @DisplayName("密码正确时归还额度，并用该用户的 token 版本号签发")
    void releasesBudgetAndSignsWithTokenVersionOnSuccess() {
        User user = existingUser(1, 5);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(jwtService.generateToken(7L, 5)).thenReturn("signed-token");

        LoginResponse response = userService.login(request("correct"), CLIENT_IP);

        assertThat(response.getToken()).isEqualTo("signed-token");
        verify(loginAttemptLimiter).release(USERNAME, CLIENT_IP);
    }

    @Test
    @DisplayName("密码错误不归还额度：否则失败次数会被抹掉，限流形同虚设")
    void doesNotReleaseBudgetOnBadCredentials() {
        User user = existingUser(1, 0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> userService.login(request("wrong"), CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(401);

        verify(loginAttemptLimiter, never()).release(any(), any());
        verify(jwtService, never()).generateToken(anyLong(), anyInt());
    }

    @Test
    @DisplayName("用户不存在同样算一次失败，且不泄露账号是否存在")
    void countsUnknownUsernameAsFailureWithoutLeakingExistence() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userService.login(request("whatever"), CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");

        verify(loginAttemptLimiter).tryAcquire(USERNAME, CLIENT_IP);
        verify(loginAttemptLimiter, never()).release(any(), any());
    }

    @Test
    @DisplayName("账号被禁用返回 403；密码已验证通过，额度要归还")
    void releasesBudgetForDisabledAccount() {
        User user = existingUser(0, 0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> userService.login(request("correct"), CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(403);

        verify(loginAttemptLimiter).release(USERNAME, CLIENT_IP);
    }

    @Test
    @DisplayName("被限流拦住时直接 429，连用户表都不查（省掉一次无意义的 BCrypt）")
    void lockoutShortCircuitsBeforeTouchingTheDatabase() {
        doThrow(new BusinessException(429, "登录失败次数过多，请 15 分钟后再试"))
                .when(loginAttemptLimiter).tryAcquire(USERNAME, CLIENT_IP);

        assertThatThrownBy(() -> userService.login(request("correct"), CLIENT_IP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);

        verify(userMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("用户 tokenVersion 为 null 时按 0 处理，不能抛 NPE")
    void treatsNullTokenVersionAsZero() {
        User user = existingUser(1, 0);
        user.setTokenVersion(null);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(jwtService.generateToken(anyLong(), anyInt())).thenReturn("token");

        userService.login(request("correct"), CLIENT_IP);

        verify(jwtService).generateToken(7L, 0);
    }

    @Test
    @DisplayName("登出把该用户所有 token 一并作废")
    void logoutBumpsTokenVersion() {
        lenient().when(userMapper.bumpTokenVersion(7L)).thenReturn(1);

        com.notepad.common.UserContext.setUserId(7L);
        try {
            userService.logout();
            verify(userMapper).bumpTokenVersion(7L);
        } finally {
            com.notepad.common.UserContext.clear();
        }
    }
}
