package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.dto.user.Location;
import com.spingbootinit.model.dto.user.RegisterRequest;
import com.spingbootinit.model.dto.user.UpdatePasswordRequest;
import com.spingbootinit.model.dto.user.UserUpdateMyRequest;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.service.impl.UserServiceImpl;
import com.spingbootinit.utils.JwtUtils;
import com.spingbootinit.utils.RedisUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountSettingsServiceTest {
    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), User.class);
    }

    @Test
    void registerWithoutEmailDoesNotTreatOtherUnboundAccountsAsDuplicate() {
        UserServiceImpl service = mock(UserServiceImpl.class, CALLS_REAL_METHODS);
        PasswordProcessor processor = mock(PasswordProcessor.class);
        ReflectionTestUtils.setField(service, "passwordProcessor", processor);
        doReturn(0L).when(service).count(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
        doReturn(true).when(service).save(any(User.class));
        RegisterRequest request = new RegisterRequest();
        request.setUsername("new_student");
        request.setPassword("abc123");
        request.setConfirmPassword("abc123");
        request.setEmail("");

        service.register(request);

        verify(service, times(1)).count(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(service).save(saved.capture());
        assertNull(saved.getValue().getEmail());
    }

    @Test
    void clearingBirthdayAndLocationWritesNull() {
        UserServiceImpl service = mock(UserServiceImpl.class, CALLS_REAL_METHODS);
        doReturn(true).when(service).update(any(LambdaUpdateWrapper.class));
        UserUpdateMyRequest request = new UserUpdateMyRequest();
        request.setBirthday("");
        request.setLocationCodes(new Location());

        service.updateMyProfile(42L, request);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(service).update(captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("birthday"));
        assertTrue(sqlSet.toLowerCase().contains("location"), sqlSet);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(null), sqlSet);
    }

    @Test
    void changingPasswordRejectsUnconfirmedValue() {
        UserServiceImpl service = mock(UserServiceImpl.class, CALLS_REAL_METHODS);
        PasswordProcessor processor = mock(PasswordProcessor.class);
        ReflectionTestUtils.setField(service, "passwordProcessor", processor);
        User user = new User();
        user.setPassword("hash");
        doReturn(user).when(service).getById(42L);
        when(processor.matches("oldPassword1", "hash")).thenReturn(true);
        UpdatePasswordRequest request = passwordRequest("oldPassword1", "newPassword1", "different2");

        assertThrows(BusinessException.class, () -> service.updatePassword(42L, request));
        verify(service, never()).updateById(any(User.class));
    }

    @Test
    void changingPasswordRevokesExistingTokens() {
        UserServiceImpl service = mock(UserServiceImpl.class, CALLS_REAL_METHODS);
        PasswordProcessor processor = mock(PasswordProcessor.class);
        RedisUtil redis = mock(RedisUtil.class);
        JwtUtils jwt = mock(JwtUtils.class);
        ReflectionTestUtils.setField(service, "passwordProcessor", processor);
        ReflectionTestUtils.setField(service, "redisUtil", redis);
        ReflectionTestUtils.setField(service, "jwtUtils", jwt);
        User user = new User();
        user.setPassword("hash");
        doReturn(user).when(service).getById(42L);
        doReturn(true).when(service).updateById(user);
        when(processor.matches("oldPassword1", "hash")).thenReturn(true);
        when(processor.encode("newPassword1")).thenReturn("new-hash");
        when(jwt.getRefreshTtlSeconds()).thenReturn(604800L);

        service.updatePassword(42L, passwordRequest("oldPassword1", "newPassword1", "newPassword1"));

        assertEquals("new-hash", user.getPassword());
        verify(redis).deleteKeysByPrefix("auth:refresh:42:");
        verify(redis).set(eq("auth:active-session:42"), anyString(), eq(604800L), eq(TimeUnit.SECONDS));
    }

    private UpdatePasswordRequest passwordRequest(String oldPassword, String newPassword, String confirmPassword) {
        UpdatePasswordRequest request = new UpdatePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        request.setConfirmNewPassword(confirmPassword);
        return request;
    }
}
