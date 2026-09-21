package com.notepad.controller;

import com.notepad.common.ClientIpResolver;
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
        LoginResponse result = userService.login(request, ClientIpResolver.resolve(servletRequest));
        response.addHeader(HttpHeaders.SET_COOKIE,
                jwtCookieService.create(result.getToken(), servletRequest.isSecure()));
        return Result.ok(result);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        userService.logout();
        // 必须用 setHeader：JwtInterceptor 的 preHandle 已经 addHeader 刷新过一次
        // notepad_access，addHeader 会追加出第二个同名 Set-Cookie，靠浏览器「取最后一个」
        // 兜着 —— 一旦顺序被重排，过期 cookie 会留下，/uploads/** 会一直带着失效 token 请求，
        // 而 <img> 的 401 不触发前端拦截器，表现为图片全裂且毫无提示。
        response.setHeader(HttpHeaders.SET_COOKIE, jwtCookieService.clear(request.isSecure()));
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
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                      HttpServletRequest servletRequest,
                                      HttpServletResponse response) {
        userService.resetPassword(request);
        // 密码已变更，所有旧 token 失效，顺手清掉本地可能残留的 httpOnly cookie
        response.setHeader(HttpHeaders.SET_COOKIE, jwtCookieService.clear(servletRequest.isSecure()));
        return Result.ok();
    }

    @PostMapping("/password/change-code")
    public Result<Void> sendChangePasswordCode() {
        userService.sendChangePasswordCode();
        return Result.ok();
    }

    @PostMapping("/password/change")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest servletRequest,
                                       HttpServletResponse response) {
        userService.changePassword(request);
        // 改密后当前 token 已被吊销，但 preHandle 刚刷新过一次 cookie，指向的已是死 token，
        // 这里必须用 setHeader 把它覆盖掉（logout 处有同样的说明）
        response.setHeader(HttpHeaders.SET_COOKIE, jwtCookieService.clear(servletRequest.isSecure()));
        return Result.ok();
    }
}
