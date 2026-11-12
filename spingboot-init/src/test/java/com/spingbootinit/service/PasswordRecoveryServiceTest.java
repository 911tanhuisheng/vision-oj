package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.utils.RedisUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordRecoveryServiceTest {
    @Mock private UserService userService;
    @Mock private PasswordProcessor passwordProcessor;
    @Mock private RedisUtil redisUtil;
    @InjectMocks private PasswordRecoveryService recoveryService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(recoveryService, "mailFrom", "test@example.com");
    }

    @Test
    void validCodeChangesPasswordAndRevokesOldSessions() {
        String code = "123456";
        String emailHash = DigestUtils.sha256Hex("student@example.com");
        when(redisUtil.get("auth:reset:code:" + emailHash)).thenReturn(DigestUtils.sha256Hex(code));
        when(redisUtil.delete("auth:reset:code:" + emailHash)).thenReturn(true);
        User user = new User();
        user.setId(42L);
        when(userService.getOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordProcessor.encode("abc123")).thenReturn("encoded");
        when(userService.updateById(user)).thenReturn(true);

        recoveryService.resetPassword(" Student@Example.com ", code, "abc123");

        assertEquals("encoded", user.getPassword());
        verify(redisUtil).deleteKeysByPrefix("auth:refresh:42:");
        verify(redisUtil).set(eq("auth:active-session:42"), anyString(), eq(7L), eq(TimeUnit.DAYS));
    }

    @Test
    void wrongCodeDoesNotChangePassword() {
        String emailHash = DigestUtils.sha256Hex("student@example.com");
        when(redisUtil.get("auth:reset:code:" + emailHash)).thenReturn(DigestUtils.sha256Hex("123456"));

        assertThrows(BusinessException.class,
                () -> recoveryService.resetPassword("student@example.com", "999999", "abc123"));

        verify(redisUtil).set(eq("auth:reset:attempts:" + emailHash), eq("1"), eq(10L), eq(TimeUnit.MINUTES));
        verify(userService, never()).updateById(any(User.class));
    }
}
