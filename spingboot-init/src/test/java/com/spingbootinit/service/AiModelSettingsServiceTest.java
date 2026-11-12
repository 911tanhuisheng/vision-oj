package com.spingbootinit.service;

import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.config.BailianAssistProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiModelSettingsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BailianAssistProperties site = new BailianAssistProperties();
    private final AiModelSettingsService settings = new AiModelSettingsService(jdbc, site);

    @BeforeEach
    void key() {
        ReflectionTestUtils.setField(settings, "encryptionKey",
                Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void savesEncryptedKeyAndOnlyOwnerCanResolveIt() {
        settings.save(7L, "DASHSCOPE", "qwen-plus", "private-key-3456");
        ArgumentCaptor<String> cipher = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("INSERT INTO user_ai_provider_config"), eq(7L), eq("DASHSCOPE"),
                eq("qwen-plus"), cipher.capture(), eq("3456"));
        assertFalse(cipher.getValue().contains("private-key-3456"));
        when(jdbc.query(startsWith("SELECT model_id"), any(RowMapper.class), eq(7L), eq("DASHSCOPE")))
                .thenReturn(Collections.singletonList(new String[]{"qwen-plus", cipher.getValue()}));
        when(jdbc.query(startsWith("SELECT model_id"), any(RowMapper.class), eq(8L), eq("DASHSCOPE")))
                .thenReturn(List.of());

        assertEquals("private-key-3456", settings.resolve(7L, "DASHSCOPE", null).apiKey());
        assertThrows(BusinessException.class, () -> settings.resolve(8L, "DASHSCOPE", null));
    }

    @Test
    void providerUrlCannotComeFromUserInput() {
        assertThrows(BusinessException.class, () -> settings.resolve(7L, "http://127.0.0.1:8080", "qwen-plus"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void siteDefaultIgnoresUserSelectedModel() {
        site.setEnabled(true);
        site.setApiKey("admin-key");
        site.setModel("qwen-plus");
        assertEquals("qwen-plus", settings.resolve(7L, "SITE_DEFAULT", "another-model").modelId());
        verifyNoInteractions(jdbc);
    }

    @Test
    void connectionErrorsDistinguishAuthenticationFromAccess() {
        assertTrue(AiModelSettingsService.connectionFailure("DEEPSEEK", 401).contains("密钥认证失败"));
        assertTrue(AiModelSettingsService.connectionFailure("DEEPSEEK", 403).contains("拒绝访问"));
        assertTrue(AiModelSettingsService.connectionFailure("DEEPSEEK", 402).contains("余额不足"));
        assertTrue(AiModelSettingsService.connectionFailure("DASHSCOPE", 403).contains("模型权限"));
    }
}
