package com.notepad.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务（基于 spring-boot-starter-mail + JavaMailSender）。
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String from;

    /**
     * 发送注册验证码邮件。
     */
    public void sendVerificationCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("【云记事本】邮箱验证码");
        message.setText(String.format("您的验证码是：%s，5 分钟内有效，请勿泄露给他人。", code));
        mailSender.send(message);
    }

    /**
     * 发送笔记提醒邮件。
     */
    public void sendReminder(String to, String noteTitle) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("【云记事本】笔记提醒");
        message.setText(String.format("您为「%s」设置的提醒时间已到，记得查看你的笔记哦～", noteTitle));
        mailSender.send(message);
    }
}
