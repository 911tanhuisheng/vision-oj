package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class EmailBindingService {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource private UserService userService;
    @Resource private PasswordProcessor passwordProcessor;
    @Resource private RedisUtil redisUtil;
    @Resource private JavaMailSender mailSender;
    @Value("${spring.mail.username:}") private String mailFrom;

    public void sendCode(Long userId, String rawEmail, String password) {
        String email = normalize(rawEmail);
        User user = verifyUser(userId, password);
        checkNewEmail(user, email);
        if (mailFrom == null || mailFrom.isBlank()) {
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "邮箱服务尚未配置，请联系管理员");
        }
        String key = codeKey(userId, email);
        String limitKey = key + ":limit";
        if (!Boolean.TRUE.equals(redisUtil.setIfAbsent(limitKey, "1", 60, TimeUnit.SECONDS))) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), "发送太频繁，请60秒后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisUtil.set(key, DigestUtils.sha256Hex(code), 10, TimeUnit.MINUTES);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("码跃 OJ · 绑定邮箱验证码");
            message.setText("您正在绑定码跃 OJ 账号邮箱。验证码：" + code + "\n10分钟内有效。若非本人操作，请忽略本邮件。");
            mailSender.send(message);
        } catch (Exception ex) {
            redisUtil.delete(key);
            redisUtil.delete(limitKey);
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "邮件发送失败，请稍后再试");
        }
    }

    public void bind(Long userId, String rawEmail, String password, String code) {
        String email = normalize(rawEmail);
        User user = verifyUser(userId, password);
        checkNewEmail(user, email);
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请输入6位邮件验证码");
        }
        String key = codeKey(userId, email);
        Object saved = redisUtil.get(key);
        if (saved == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期，请重新获取");
        }
        String attemptsKey = key + ":attempts";
        Object rawAttempts = redisUtil.get(attemptsKey);
        int attempts = rawAttempts == null ? 0 : Integer.parseInt(rawAttempts.toString());
        if (attempts >= 5) {
            redisUtil.delete(key);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "错误次数过多，请重新获取验证码");
        }
        if (!saved.toString().equals(DigestUtils.sha256Hex(code))) {
            redisUtil.set(attemptsKey, String.valueOf(attempts + 1), 10, TimeUnit.MINUTES);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误，请检查后重试");
        }
        if (!Boolean.TRUE.equals(redisUtil.delete(key))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已使用，请重新获取");
        }
        boolean updated;
        try {
            updated = userService.update(new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId).set(User::getEmail, email));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该邮箱已被其他账号使用");
        }
        if (!updated) {
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "邮箱绑定失败，请重新获取验证码");
        }
        redisUtil.delete(attemptsKey);
    }

    private User verifyUser(Long userId, String password) {
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        User user = userService.getById(userId);
        if (user == null || password == null || !passwordProcessor.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "当前登录密码错误");
        }
        return user;
    }

    private void checkNewEmail(User user, String email) {
        if (email.equalsIgnoreCase(String.valueOf(user.getEmail()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "新邮箱与当前邮箱相同");
        }
        if (userService.count(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该邮箱已被其他账号使用");
        }
    }

    private String codeKey(Long userId, String email) {
        return "auth:email-bind:" + userId + ":" + DigestUtils.sha256Hex(email);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
