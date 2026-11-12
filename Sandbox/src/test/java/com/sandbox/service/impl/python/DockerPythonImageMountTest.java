package com.sandbox.service.impl.python;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.sandbox.config.SandboxProperties;
import com.sandbox.model.ExecuteCodeRequest;
import com.sandbox.sandbox.template.SandboxContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerPythonImageMountTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void questionImageLivesOutsideWritableCodeDirectoryAndMountsReadOnly() {
        SandboxProperties properties = new SandboxProperties();
        properties.setTmpDir(temporaryRoot.toString());
        DockerPythonCodeSandbox sandbox = new DockerPythonCodeSandbox();
        ReflectionTestUtils.setField(sandbox, "sandboxProperties", properties);
        SandboxContext context = new SandboxContext(ExecuteCodeRequest.builder()
                .language("python").code("print(input())").inputList(List.of("unused"))
                .imageBase64List(List.of(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})))
                .build());
        context.inputList = List.of("unused");

        sandbox.saveCodeToFile(context);
        try {
            assertFalse(context.imageDir.toPath().startsWith(context.workDir.toPath()));
            assertTrue(Files.exists(context.imageDir.toPath().resolve(".image_0.jpg")));
            assertEquals("/input-images/.image_0.jpg", context.inputList.get(0));
            List<Bind> binds = DockerPythonCodeSandbox.buildBinds(
                    context.workDir.getAbsolutePath(), context.imageDir.getAbsolutePath());
            assertEquals(AccessMode.ro, binds.get(1).getAccessMode());
            assertEquals("/input-images", binds.get(1).getVolume().getPath());
        } finally {
            sandbox.cleanup(context);
        }
        assertFalse(Files.exists(context.imageDir.toPath()));
        assertFalse(Files.exists(context.workDir.toPath()));
    }
}
