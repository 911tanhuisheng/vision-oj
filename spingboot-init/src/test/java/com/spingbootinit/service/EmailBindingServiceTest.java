package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.utils.RedisUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailBindingServiceTest {
    @Mock private UserService userService;
    @Mock private PasswordProcessor passwordProcessor;
    @Mock private RedisUtil redisUtil;
    @InjectMocks private EmailBindingService service;

    @Test
    void wrongCurrentPasswordCannotBindEmail() {
        User user = new User();
        user.setPassword("stored-hash");
        when(userService.getById(42L)).thenReturn(user);

        assertThrows(BusinessException.class,
                () -> service.bind(42L, "student@qq.com", "wrong", "123456"));

        verify(userService, never()).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void verifiedEmailCanBeBoundOnce() {
        User user = new User();
        user.setPassword("stored-hash");
        when(userService.getById(42L)).thenReturn(user);
        when(passwordProcessor.matches("correct-password", "stored-hash")).thenReturn(true);
        String key = "auth:email-bind:42:" + DigestUtils.sha256Hex("student@qq.com");
        when(redisUtil.get(key)).thenReturn(DigestUtils.sha256Hex("123456"));
        when(redisUtil.delete(key)).thenReturn(true);
        when(userService.update(any(LambdaUpdateWrapper.class))).thenReturn(true);

        service.bind(42L, " Student@QQ.com ", "correct-password", "123456");

        verify(userService).count(any(LambdaQueryWrapper.class));
        verify(userService).update(any(LambdaUpdateWrapper.class));
        verify(redisUtil).delete(key);
    }
}
