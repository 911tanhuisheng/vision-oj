package com.sandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    private Auth auth = new Auth();

    private String tmpDir = "tmpCode";

    private String tmpCodeDir;

    private RateLimit rateLimit = new RateLimit();

    private Docker docker = new Docker();

    private Execution execution = new Execution();

    private Security security = new Security();

    public String getTmpCodeDir() {
        return tmpCodeDir == null || tmpCodeDir.isBlank() ? tmpDir : tmpCodeDir;
    }

    @Data
    public static class Auth {
        private String key = "";
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private boolean fallbackEnabled = true;
        private long windowSeconds = 60;
        private long limit = 10;
        private int localMaxKeys = 10_000;
    }

    @Data
    public static class Docker {
        private boolean startupCheckEnabled = true;
        private String javaImage = "eclipse-temurin:17-jdk";
        private String pythonImage = "mayue-oj-python-vision:1.0";
        private String cppImage = "gcc:13";
    }

    @Data
    public static class Execution {
        private int maxConcurrent = 4;
        /**
         * 并发名额耗尽时，最多等待多久再拒绝（毫秒）。
         * 0 表示不等待，直接拒绝（与旧行为一致）。
         */
        private long acquireTimeoutMs = 30_000L;
        private long localCompileTimeoutMs = 10_000L;
        private long localRunTimeoutMs = 5_000L;
        private long dockerCompileTimeoutMs = 10_000L;
        private long dockerRunTimeoutMs = 5_000L;
    }

    @Data
    public static class Security {
        private int maxCodeLength = 100_000;
        private int maxCaseCount = 20;
    }
}
