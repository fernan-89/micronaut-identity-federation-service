package com.thinklab.application.usecase;

import com.thinklab.application.config.FederationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuerPolicyTest {

    @Test
    @DisplayName("https is always trusted; http only on a loopback host; anything else, a missing host, a malformed URL or nothing is not")
    void defaultPolicy() {
        IssuerPolicy policy = new IssuerPolicy(new FederationProperties());

        assertTrue(policy.trusted("https://login.example.com/token"));
        assertTrue(policy.trusted("http://localhost:9000"));
        assertTrue(policy.trusted("http://127.0.0.1/x"));
        assertTrue(policy.trusted("http://[::1]:9000"));
        assertFalse(policy.trusted("http://login.example.com"));
        assertFalse(policy.trusted("http://mock-oidc:9000"));
        assertFalse(policy.trusted("ftp://localhost/x"));
        assertFalse(policy.trusted("http:///path"));
        assertFalse(policy.trusted("not a url"));
        assertFalse(policy.trusted(null));
    }

    @Test
    @DisplayName("an operator can allow plain http for named hosts (a containerised test provider), and only those")
    void insecureHosts() {
        FederationProperties properties = new FederationProperties();
        properties.setInsecureHosts(List.of("mock-oidc"));
        IssuerPolicy policy = new IssuerPolicy(properties);

        assertEquals(List.of("mock-oidc"), properties.getInsecureHosts());
        assertTrue(policy.trusted("http://mock-oidc:9000"));
        assertFalse(policy.trusted("http://other-host:9000"));
    }

    @Test
    @DisplayName("requireTrusted passes a trusted URL and refuses the rest with a validation error")
    void requireTrusted() {
        IssuerPolicy policy = new IssuerPolicy(new FederationProperties());

        assertDoesNotThrow(() -> policy.requireTrusted("https://login.example.com"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireTrusted("http://login.example.com"));
    }

    @Test
    @DisplayName("the properties default to the local callback, a ten-minute window and no insecure hosts")
    void propertyDefaults() {
        FederationProperties properties = new FederationProperties();

        assertEquals("http://localhost:5173/api/identity-federation/v1/login/callback", properties.getRedirectUri());
        assertEquals(600, properties.getTransactionTtlSeconds());
        assertEquals(List.of(), properties.getInsecureHosts());
    }
}
