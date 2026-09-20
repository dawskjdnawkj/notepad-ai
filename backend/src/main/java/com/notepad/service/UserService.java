package com.notepad.service;

import com.notepad.dto.ChangePasswordRequest;
import com.notepad.dto.LoginRequest;
import com.notepad.dto.RegisterRequest;
import com.notepad.dto.ResetPasswordRequest;
import com.notepad.dto.SendCodeRequest;
import com.notepad.vo.LoginResponse;
import com.notepad.vo.UserVO;

public interface UserService {

    LoginResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    void logout();

    UserVO getCurrentUser();

    void sendCode(SendCodeRequest request);

    void sendPasswordResetCode(SendCodeRequest request);

    void resetPassword(ResetPasswordRequest request);

    void sendChangePasswordCode();

    void changePassword(ChangePasswordRequest request);
}
