package com.thinklab.infrastructure.adapter.out.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EnvClientSecretAdapterTest {

    @Test
    @DisplayName("a secret is read from the environment at the moment it is asked for; a missing or blank variable is not configured")
    void resolves() {
        Map<String, String> environment = Map.of("OIDC_SECRET", "s3cret", "BLANK_SECRET", "  ");
        EnvClientSecretAdapter adapter = new EnvClientSecretAdapter(environment::get);

        assertEquals(Optional.of("s3cret"), adapter.resolve("OIDC_SECRET"));
        assertEquals(Optional.empty(), adapter.resolve("BLANK_SECRET"));
        assertEquals(Optional.empty(), adapter.resolve("NOT_SET"));
    }

    @Test
    @DisplayName("the default constructor reads the real process environment")
    void defaultConstructor() {
        EnvClientSecretAdapter adapter = new EnvClientSecretAdapter();

        assertNotNull(adapter.resolve("THINKLAB_SURELY_NOT_A_REAL_VARIABLE_NAME"));
        assertEquals(Optional.empty(), adapter.resolve("THINKLAB_SURELY_NOT_A_REAL_VARIABLE_NAME"));
    }
}
