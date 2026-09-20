package com.notepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notepad.common.BusinessException;
import com.notepad.common.UserContext;
import com.notepad.config.JwtService;
import com.notepad.entity.Notebook;
import com.notepad.mapper.NotebookMapper;
import com.notepad.dto.LoginRequest;
import com.notepad.dto.RegisterRequest;
import com.notepad.dto.ResetPasswordRequest;
import com.notepad.dto.ChangePasswordRequest;
import com.notepad.dto.SendCodeRequest;
import com.notepad.entity.User;
import com.notepad.mapper.UserMapper;
import com.notepad.service.MailService;
import com.notepad.service.UserService;
import com.notepad.service.VerificationCodeService;
import com.notepad.vo.LoginResponse;
import com.notepad.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final NotebookMapper notebookMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final VerificationCodeService verificationCodeService;

    @Override
    public void sendCode(SendCodeRequest request) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, request.getEmail()));
        if (count != null && count > 0) {
            throw new BusinessException(409, "该邮箱已被注册");
        }
        String code = verificationCodeService.generate(request.getEmail());
        mailService.sendVerificationCode(request.getEmail(), code);
    }

    @Override
    public void sendPasswordResetCode(SendCodeRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, request.getEmail()));
        if (user == null) {
            throw new BusinessException(404, "该邮箱未注册");
        }
        String code = verificationCodeService.generate(request.getEmail());
        mailService.sendVerificationCode(request.getEmail(), code);
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, request.getEmail()));
        if (user == null) {
            throw new BusinessException(404, "该邮箱未注册");
        }
        verificationCodeService.verify(request.getEmail(), request.getCode());

        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);
    }

    @Override
    public void sendChangePasswordCode() {
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new BusinessException(400, "当前账号未绑定邮箱，无法修改密码");
        }
        String code = verificationCodeService.generate(user.getEmail());
        mailService.sendVerificationCode(user.getEmail(), code);
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new BusinessException(400, "当前账号未绑定邮箱，无法修改密码");
        }
        verificationCodeService.verify(user.getEmail(), request.getCode());

        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request) {
        // 先校验邮箱验证码，通过后立即失效
        verificationCodeService.verify(request.getEmail(), request.getCode());

        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(409, "用户名已存在");
        }

        Long emailCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, request.getEmail()));
        if (emailCount != null && emailCount > 0) {
            throw new BusinessException(409, "该邮箱已被注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setStatus(1);
        userMapper.insert(user);

        // 注册成功自动创建默认笔记本
        Notebook notebook = new Notebook();
        notebook.setUserId(user.getId());
        notebook.setName("默认笔记本");
        notebook.setIsDefault(1);
        notebookMapper.insert(notebook);

        // 注册成功即自动登录（签发 JWT）
        String token = jwtService.generateToken(user.getId());
        return new LoginResponse(token, UserVO.from(user));
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }

        String token = jwtService.generateToken(user.getId());
        return new LoginResponse(token, UserVO.from(user));
    }

    @Override
    public void logout() {
        // JWT 为无状态认证：服务端不保存登录态，登出由前端清除 token 完成。
        // 如需服务端强制失效（如改密码后踢下线），可引入 Redis 黑名单（P1）。
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return UserVO.from(user);
    }
}
