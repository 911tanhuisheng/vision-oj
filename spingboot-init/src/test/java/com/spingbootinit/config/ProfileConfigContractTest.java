package com.spingbootinit.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileConfigContractTest {

    @Test
    void profileFilesKeepSecretsOutOfCommonConfigAndDefineSafeDefaults() throws IOException {
        String common = resource("application.yml");
        String dev = resource("application-dev.yml");
        String prod = resource("application-prod.yml");

        assertFalse(common.contains("application-local.yml"));
        assertFalse(common.contains("192.168."));
        assertFalse(common.contains("123456"));
        assertTrue(dev.contains("optional:file:./spingboot-init/application-local.yml"));
        assertTrue(dev.contains("optional:file:./application-local.yml"));
        assertFalse(prod.contains("application-local.yml"));
        assertTrue(dev.contains("127.0.0.1"));
        assertRequiredSecretsComeFromEnvironment(dev);
        assertTrue(dev.contains("3306/test"));
        assertFalse(dev.contains("3306/tan"));
        assertFalse(prod.contains("192.168."));
        assertTrue(prod.contains("3306/tan"));
        assertFalse(prod.contains("3306/test"));
        assertRequiredSecretsComeFromEnvironment(prod);
        assertTrue(prod.contains("${MAIL_USERNAME:}"));
        assertTrue(prod.contains("${MAIL_PASSWORD:}"));
        assertTrue(prod.contains("VISION_AI_ENABLED:false"));
        assertTrue(prod.contains("IMAGE_GENERATION_ENABLED:false"));
        assertTrue(prod.contains("BAILIAN_ENABLED:false"));
    }

    private void assertRequiredSecretsComeFromEnvironment(String config) {
        for (String name : new String[]{"MYSQL_PASSWORD", "JWT_SECRET", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "SANDBOX_AUTH_KEY"}) {
            assertTrue(config.contains("${" + name + "}"), name + " must not have a bundled default");
            assertFalse(config.contains("${" + name + ":"), name + " must not expose a default credential");
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing classpath resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
