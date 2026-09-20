package com.notepad.controller;

import com.notepad.common.Result;
import com.notepad.config.JwtCookieService;
import com.notepad.dto.ChangePasswordRequest;
import com.notepad.dto.LoginRequest;
import com.notepad.dto.RegisterRequest;
import com.notepad.dto.ResetPasswordRequest;
import com.notepad.dto.SendCodeRequest;
import com.notepad.service.UserService;
import com.notepad.vo.LoginResponse;
import com.notepad.vo.UserVO;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtCookieService jwtCookieService;

    @PostMapping("/send-code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        userService.sendCode(request);
        return Result.ok();
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest servletRequest,
                                          HttpServletResponse response) {
        LoginResponse result = userService.register(request);
        response.addHeader(HttpHeaders.SET_COOKIE,
                jwtCookieService.create(result.getToken(), servletRequest.isSecure()));
        return Result.ok(result);
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest servletRequest,
                                       HttpServletResponse response) {
        LoginResponse result = userService.login(request);
        response.addHeader(HttpHeaders.SET_COOKIE,
                jwtCookieService.create(result.getToken(), servletRequest.isSecure()));
        return Result.ok(result);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        userService.logout();
        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookieService.clear(request.isSecure()));
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.getCurrentUser());
    }

    @PostMapping("/password/reset-code")
    public Result<Void> sendPasswordResetCode(@Valid @RequestBody SendCodeRequest request) {
        userService.sendPasswordResetCode(request);
        return Result.ok();
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request);
        return Result.ok();
    }

    @PostMapping("/password/change-code")
    public Result<Void> sendChangePasswordCode() {
        userService.sendChangePasswordCode();
        return Result.ok();
    }

    @PostMapping("/password/change")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return Result.ok();
    }
}
