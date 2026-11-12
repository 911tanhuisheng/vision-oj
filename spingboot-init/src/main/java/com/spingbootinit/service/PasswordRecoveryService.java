package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spingbootinit.common.constant.RedisKeyConstants;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.utils.ClientIpUtils;
import com.spingbootinit.utils.RedisUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PasswordRecoveryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GENERIC_MESSAGE = "如果该邮箱已绑定账号，验证码将在几分钟内送达。请检查收件箱及垃圾邮件。";

    @Resource private UserService userService;
    @Resource private PasswordProcessor passwordProcessor;
    @Resource private RedisUtil redisUtil;
    @Resource private JavaMailSender mailSender;
    @Value("${spring.mail.username:}") private String mailFrom;

    public String sendCode(String rawEmail, HttpServletRequest request) {
        String email = normalize(rawEmail);
        if (mailFrom == null || mailFrom.isBlank()) {
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "邮箱服务尚未配置，请联系管理员");
        }
        String emailHash = DigestUtils.sha256Hex(email);
        String ipHash = DigestUtils.sha256Hex(ClientIpUtils.resolve(request));
        if (!Boolean.TRUE.equals(redisUtil.setIfAbsent("auth:reset:ip:" + ipHash, "1", 15, TimeUnit.SECONDS))
                || !Boolean.TRUE.equals(redisUtil.setIfAbsent("auth:reset:limit:" + emailHash, "1", 60, TimeUnit.SECONDS))) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), "发送太频繁，请稍后再试");
        }
        User user = findByEmail(email);
        if (user == null) {
            return GENERIC_MESSAGE;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String key = "auth:reset:code:" + emailHash;
        String attemptsKey = "auth:reset:attempts:" + emailHash;
        redisUtil.set(key, DigestUtils.sha256Hex(code), 10, TimeUnit.MINUTES);
        redisUtil.delete(attemptsKey);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("码跃 OJ · 重置密码验证码");
            message.setText("您正在找回码跃 OJ 账号密码。验证码：" + code + "\n10 分钟内有效。若非本人操作，请忽略本邮件。请勿将验证码告诉他人。");
            mailSender.send(message);
        } catch (Exception ex) {
            redisUtil.delete(key);
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "邮件发送失败，请稍后再试或联系管理员");
        }
        return GENERIC_MESSAGE;
    }

    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalize(rawEmail);
        String emailHash = DigestUtils.sha256Hex(email);
        String key = "auth:reset:code:" + emailHash;
        String attemptsKey = "auth:reset:attempts:" + emailHash;
        Object stored = redisUtil.get(key);
        if (stored == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期，请重新获取");
        }
        Object attemptRaw = redisUtil.get(attemptsKey);
        int attempts = attemptRaw == null ? 0 : Integer.parseInt(attemptRaw.toString());
        if (attempts >= 5) {
            redisUtil.delete(key);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误次数过多，请重新获取");
        }
        if (!stored.toString().equals(DigestUtils.sha256Hex(code))) {
            redisUtil.set(attemptsKey, String.valueOf(attempts + 1), 10, TimeUnit.MINUTES);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误，请检查后重试");
        }
        // 成功时先消费验证码，避免并发重放。
        if (!Boolean.TRUE.equals(redisUtil.delete(key))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已使用，请重新获取");
        }
        User user = findByEmail(email);
        if (user == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已失效，请重新获取");
        }
        user.setPassword(passwordProcessor.encode(newPassword));
        if (!userService.updateById(user)) {
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "重置失败，请重新获取验证码后再试");
        }
        redisUtil.delete(attemptsKey);
        // 轮换会话标识，使现有 access token 立即失效；同时清除 refresh token。
        redisUtil.deleteKeysByPrefix(RedisKeyConstants.refreshTokenPattern(user.getId()));
        redisUtil.set(RedisKeyConstants.activeSessionKey(user.getId()), UUID.randomUUID().toString(), 7, TimeUnit.DAYS);
    }

    private User findByEmail(String email) {
        return userService.getOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
