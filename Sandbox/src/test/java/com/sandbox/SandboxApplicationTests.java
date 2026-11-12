package com.sandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.sandbox.model.ExecuteCodeRequest;
import com.sandbox.model.ExecuteCodeResponse;
import com.sandbox.service.CodeSandboxFactory;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SpringBootTest(properties = "sandbox.auth.key=test-only-sandbox-secret-32-characters")
class SandboxApplicationTests {
    @Resource
    private CodeSandboxFactory codeSandboxFactory;

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
    void contextLoads() throws InterruptedException {
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.cpp", StandardCharsets.UTF_8);

        ExecuteCodeRequest req = ExecuteCodeRequest.builder()
                .language("cpp")
                .code(code)
                .inputList(Arrays.asList("1 2", "10 20"))
                .build();
        ExecuteCodeResponse resp = codeSandboxFactory.doExecuteCode(req);
        System.out.println(resp);
    }

}
