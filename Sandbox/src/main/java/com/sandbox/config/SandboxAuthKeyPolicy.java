package com.sandbox.config;

/** Reject sample credentials before the code execution endpoint is exposed. */
public final class SandboxAuthKeyPolicy {

    private SandboxAuthKeyPolicy() {
    }

    public static void requireStrong(String key) {
        if (key == null || key.isBlank() || key.length() < 32
                || "123456".equals(key) || key.startsWith("replace-with-")) {
            throw new IllegalStateException(
                    "SANDBOX_AUTH_KEY 必须设置为至少 32 位的随机密钥，并与主后端保持一致");
        }
    }
}
