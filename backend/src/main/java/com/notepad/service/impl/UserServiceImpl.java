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
import com.notepad.service.LoginAttemptLimiter;
import com.notepad.service.UserService;
import com.notepad.service.VerificationCodeService;
import com.notepad.vo.LoginResponse;
import com.notepad.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final NotebookMapper notebookMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final VerificationCodeService verificationCodeService;
    private final LoginAttemptLimiter loginAttemptLimiter;

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

        // 改密与吊销放在同一条 UPDATE 里；重置密码必然发生在「账号可能已泄露」的场景，
        // 因此把该用户所有设备一并踢下线
        userMapper.updatePasswordAndBumpVersion(user.getId(), passwordEncoder.encode(request.getNewPassword()));
        log.info("event=auth.password.reset userId={}", user.getId());
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

        // 改密与吊销放在同一条 UPDATE 里；改完密码后当前 token 也立即失效，
        // 前端需要引导用户重新登录
        userMapper.updatePasswordAndBumpVersion(userId, passwordEncoder.encode(request.getNewPassword()));
        log.info("event=auth.password.changed userId={}", userId);
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
        // 显式置 0：insert 后不会回读数据库默认值，不设的话签发 token 时会拿到 null
        user.setTokenVersion(0);
        userMapper.insert(user);

        // 注册成功自动创建默认笔记本
        Notebook notebook = new Notebook();
        notebook.setUserId(user.getId());
        notebook.setName("默认笔记本");
        notebook.setIsDefault(1);
        notebookMapper.insert(notebook);

        // 注册成功即自动登录（签发 JWT）
        String token = jwtService.generateToken(user.getId(), user.getTokenVersion());
        return new LoginResponse(token, UserVO.from(user));
    }

    @Override
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 先查锁定：放在验密之前，锁定期内即使密码正确也直接 429。
        // 这里不加 @Transactional —— 方法里有 BCrypt 校验（约 50~100ms），
        // 开事务会让整个验密期间占着一条连接，并发登录能直接抽干连接池。
        loginAttemptLimiter.checkAllowed(request.getUsername(), clientIp);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 用户不存在同样计数，否则「不存在的用户名永不锁定」会变成账号是否存在的枚举信号
            loginAttemptLimiter.recordFailure(request.getUsername(), clientIp);
            log.warn("event=auth.login.rejected username={} ip={} outcome=bad_credentials accountExists={}",
                    request.getUsername(), clientIp, user != null);
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            // 走到这里说明密码已校验通过，不属于爆破尝试，因此既不计数也不清零
            log.warn("event=auth.login.rejected userId={} ip={} outcome=account_disabled",
                    user.getId(), clientIp);
            throw new BusinessException(403, "账号已被禁用");
        }

        // 只有真正登录成功才清零，避免正常用户被自己的输入错误累积到锁定
        loginAttemptLimiter.reset(request.getUsername(), clientIp);

        int tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = jwtService.generateToken(user.getId(), tokenVersion);
        log.info("event=auth.login.succeeded userId={} ip={} tokenVersion={}",
                user.getId(), clientIp, tokenVersion);
        return new LoginResponse(token, UserVO.from(user));
    }

    @Override
    public void logout() {
        // token 版本号 +1：服务端强制失效，该用户所有设备上的登录态一并作废。
        // userId 由 JwtInterceptor 在 preHandle 中写入 UserContext，此处可直接取用。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return;
        }
        userMapper.bumpTokenVersion(userId);
        log.info("event=auth.logout.succeeded userId={}", userId);
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
