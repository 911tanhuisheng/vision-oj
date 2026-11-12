package com.spingbootinit.judo.codesandbox.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spingbootinit.config.CodeSandboxProperties;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ExampleCodeSandboxIdentityTest {

    @Test
    void forwardsTrustedUserOnlyInHeaderNotRequestBody() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CodeSandboxProperties properties = new CodeSandboxProperties();
        properties.setUrl("http://localhost/api/sandbox");
        properties.setAuthKey("test-shared-key");
        ExampleCodeSandbox sandbox = new ExampleCodeSandbox();
        ReflectionTestUtils.setField(sandbox, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(sandbox, "codeSandboxProperties", properties);
        when(restTemplate.exchange(eq(properties.getUrl()), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":0,\"resultType\":\"SUCCESS\",\"outputList\":[],\"judgeInfo\":{\"message\":\"Accepted\"}}"));
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .language("python").code("print(1)").inputList(List.of(""))
                .userId(42L).build();

        sandbox.executeCode(request);

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(properties.getUrl()), eq(HttpMethod.POST),
                entity.capture(), eq(String.class));
        assertEquals("42", entity.getValue().getHeaders().getFirst("X-OJ-User-Id"));
        assertFalse(new ObjectMapper().writeValueAsString(entity.getValue().getBody()).contains("userId"));
    }
}
