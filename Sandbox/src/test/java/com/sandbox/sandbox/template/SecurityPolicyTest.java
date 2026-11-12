package com.sandbox.sandbox.template;

import com.sandbox.model.ExecuteCodeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityPolicyTest {

    private final SecurityPolicy policy = new SecurityPolicy(100_000, 20);

    @Test
    void pythonCommentAndStringDoNotTriggerImportRule() {
        assertDoesNotThrow(() -> policy.check(context("print('import os')\n# import os is not used")));
    }

    @Test
    void executablePythonImportIsStillRejected() {
        SandboxException error = assertThrows(SandboxException.class,
                () -> policy.check(context("import os\nprint(1)")));
        assertEquals(SandboxError.SECURITY_REJECT, error.error);
    }

    private static SandboxContext context(String code) {
        SandboxContext context = new SandboxContext(ExecuteCodeRequest.builder()
                .language("python").code(code).inputList(List.of("")).build());
        context.inputList = List.of("");
        return context;
    }
}
