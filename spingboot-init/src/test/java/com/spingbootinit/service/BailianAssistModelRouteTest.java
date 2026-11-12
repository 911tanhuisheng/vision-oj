package com.spingbootinit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.config.BailianAssistProperties;
import com.spingbootinit.model.dto.bailian.ProblemStructureRequest;
import com.spingbootinit.service.impl.BailianAssistService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BailianAssistModelRouteTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void structureProblemUsesSelectedPersonalModel() throws Exception {
        AtomicReference<String> sentBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String reply = "{\"choices\":[{\"message\":{\"content\":\"{\\\"title\\\":\\\"两数之和\\\",\\\"content\\\":\\\"题面\\\"}\"}}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiModelSettingsService settings = mock(AiModelSettingsService.class);
        UserService users = mock(UserService.class);
        when(settings.resolve(17L, "DEEPSEEK", "deepseek-flash"))
                .thenReturn(new AiModelSettingsService.ModelRoute("DEEPSEEK", "deepseek-flash",
                        "http://127.0.0.1:" + server.getAddress().getPort(), "test-private-key", true));
        BailianAssistService service = new BailianAssistService(new BailianAssistProperties(), new ObjectMapper(),
                mock(ContentModerationService.class), users, settings);
        ProblemStructureRequest request = new ProblemStructureRequest();
        request.setOcrText("题目图片识别出的文字");
        request.setProviderCode("DEEPSEEK");
        request.setModelId("deepseek-flash");

        assertEquals("两数之和", service.structureProblem(17L, request).getTitle());
        assertEquals("Bearer test-private-key", authorization.get());
        assertTrue(sentBody.get().contains("\"model\":\"deepseek-flash\""));
        assertTrue(sentBody.get().contains("\"response_format\":{\"type\":\"json_object\"}"));
        verify(settings).recordUsage(eq(17L), any(), eq("STRUCTURE"), anyLong(), eq(true));
    }

    @Test
    void upstreamAuthenticationErrorNeverReturnsItsBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":{\"message\":\"bad key sk-secret-123\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiModelSettingsService settings = mock(AiModelSettingsService.class);
        when(settings.resolve(eq(17L), eq("DASHSCOPE"), any()))
                .thenReturn(new AiModelSettingsService.ModelRoute("DASHSCOPE", "qwen-plus",
                        "http://127.0.0.1:" + server.getAddress().getPort(), "sk-secret-123", true));
        BailianAssistService service = new BailianAssistService(new BailianAssistProperties(), new ObjectMapper(),
                mock(ContentModerationService.class), mock(UserService.class), settings);
        ProblemStructureRequest request = new ProblemStructureRequest();
        request.setOcrText("一道题");
        request.setProviderCode("DASHSCOPE");
        request.setModelId("qwen-plus");

        BusinessException error = assertThrows(BusinessException.class, () -> service.structureProblem(17L, request));
        assertFalse(error.getMessage().contains("sk-secret-123"));
        assertTrue(error.getMessage().contains("API Key"));
    }

    @Test
    void malformedStructuredFieldsHaveActionableMessageWithoutEchoingResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String reply = "{\"choices\":[{\"message\":{\"content\":\"{\\\"title\\\":\\\"测试题\\\",\\\"judgeCase\\\":\\\"private-response\\\"}\"}}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AiModelSettingsService settings = mock(AiModelSettingsService.class);
        when(settings.resolve(eq(17L), eq("DEEPSEEK"), any()))
                .thenReturn(new AiModelSettingsService.ModelRoute("DEEPSEEK", "deepseek-flash",
                        "http://127.0.0.1:" + server.getAddress().getPort(), "test-private-key", true));
        BailianAssistService service = new BailianAssistService(new BailianAssistProperties(), new ObjectMapper(),
                mock(ContentModerationService.class), mock(UserService.class), settings);
        ProblemStructureRequest request = new ProblemStructureRequest();
        request.setOcrText("一道题");
        request.setProviderCode("DEEPSEEK");
        request.setModelId("deepseek-flash");

        BusinessException error = assertThrows(BusinessException.class, () -> service.structureProblem(17L, request));
        assertTrue(error.getMessage().contains("题目字段格式"));
        assertFalse(error.getMessage().contains("private-response"));
        assertFalse(error.getMessage().contains("test-private-key"));
    }

    @Test
    void deepSeekEmptyJsonContentRetriesOnceWithoutThinking() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sentBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = calls.incrementAndGet() == 1 ? "" : "{\\\"title\\\":\\\"重试成功\\\"}";
            String reply = "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AiModelSettingsService settings = mock(AiModelSettingsService.class);
        when(settings.resolve(eq(17L), eq("DEEPSEEK"), any()))
                .thenReturn(new AiModelSettingsService.ModelRoute("DEEPSEEK", "deepseek-flash",
                        "http://127.0.0.1:" + server.getAddress().getPort(), "test-private-key", true));
        BailianAssistService service = new BailianAssistService(new BailianAssistProperties(), new ObjectMapper(),
                mock(ContentModerationService.class), mock(UserService.class), settings);
        ProblemStructureRequest request = new ProblemStructureRequest();
        request.setOcrText("一道题");
        request.setProviderCode("DEEPSEEK");
        request.setModelId("deepseek-flash");

        assertEquals("重试成功", service.structureProblem(17L, request).getTitle());
        assertEquals(2, calls.get());
        assertTrue(sentBody.get().contains("\"thinking\":{\"type\":\"disabled\"}"));
    }
}
