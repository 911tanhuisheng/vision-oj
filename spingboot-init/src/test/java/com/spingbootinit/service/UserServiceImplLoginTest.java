package com.spingbootinit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.model.dto.user.LoginRequest;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.service.impl.UserServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplLoginTest {
    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), User.class);
    }

    @Test
    void usernameLoginLooksUpUsername() {
        assertLookupColumn("student_01", "username");
    }

    @Test
    void qqEmailLoginLooksUpEmail() {
        assertLookupColumn("  12345678@qq.com  ", "email");
    }

    private void assertLookupColumn(String account, String expectedColumn) {
        UserServiceImpl service = mock(UserServiceImpl.class, CALLS_REAL_METHODS);
        PasswordProcessor processor = mock(PasswordProcessor.class);
        ReflectionTestUtils.setField(service, "passwordProcessor", processor);
        User existing = new User();
        existing.setPassword("stored-password-hash");
        doReturn(existing).when(service).getOne(any(LambdaQueryWrapper.class));
        LoginRequest request = new LoginRequest();
        request.setUsername(account);
        request.setPassword("wrongPassword1");

        assertThrows(BusinessException.class, () -> service.login(request, mock(HttpServletRequest.class)));

        @SuppressWarnings("unchecked")
        var queryCaptor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(service).getOne(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains(expectedColumn));
    }
}
