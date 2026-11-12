package com.sandbox.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxAuthKeyPolicyTest {

    @Test
    void rejectsBlankAndExampleKeys() {
        assertThrows(IllegalStateException.class, () -> SandboxAuthKeyPolicy.requireStrong(null));
        assertThrows(IllegalStateException.class, () -> SandboxAuthKeyPolicy.requireStrong("123456"));
        assertThrows(IllegalStateException.class,
                () -> SandboxAuthKeyPolicy.requireStrong("replace-with-the-same-key-as-main-api"));
    }

    @Test
    void acceptsLongRandomKey() {
        assertDoesNotThrow(() -> SandboxAuthKeyPolicy.requireStrong(
                "a4e9d6c18b7f20395a1d4e6b8c0f2a79d5e3b1c6f8a4027e9d6b3f1a5c8e0b24"));
    }
}
