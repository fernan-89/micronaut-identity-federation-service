package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidIdentityProviderStatusException;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityProviderTest {

    private static final String EXECUTOR = "admin";
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();

    private IdentityProvider provider() {
        return IdentityProvider.createNew(id, org, "https://login.example.com/tenant/", " client-1 ", "OIDC_SECRET", null, false, EXECUTOR);
    }

    @Test
    @DisplayName("createNew registers a DISABLED provider with a trimmed issuer, the default scopes and an INITIATED entry")
    void createNew() {
        IdentityProvider provider = provider();

        assertEquals("https://login.example.com/tenant", provider.getIssuer());
        assertEquals("client-1", provider.getClientId());
        assertEquals("OIDC_SECRET", provider.getClientSecretRef());
        assertEquals(IdentityProvider.DEFAULT_SCOPES, provider.getScopes());
        assertFalse(provider.isAutoProvision());
        assertEquals(ProviderStatus.DISABLED, provider.getStatus());
        assertEquals(1, provider.getAuditTrail().size());
        assertEquals("INITIATED", provider.getAuditTrail().get(0).action());
        assertEquals(IdentityProvider.DEFAULT_SCOPES, IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", "  ", true, EXECUTOR).getScopes());
        assertTrue(IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", "openid groups", true, EXECUTOR).isAutoProvision());
        assertEquals("openid groups", IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", " openid groups ", true, EXECUTOR).getScopes());
    }

    @Test
    @DisplayName("the issuer must be an http(s) URL with a host and no query, fragment or credentials (whether plain http is acceptable is the IssuerPolicy)")
    void issuerRules() {
        for (String ok : new String[]{"https://login.example.com", "http://localhost:8099", "http://127.0.0.1:9000/realm", "http://[::1]:9000", "http://mock-oidc:9000"}) {
            IdentityProvider.createNew(id, org, ok, "c", "SECRET_X", null, false, EXECUTOR);
        }
        for (String bad : new String[]{null, "", " ", "ht tp://x", "https:///path", "http:///path", "ftp://login.example.com",
                "login.example.com", "https://login.example.com?x=1", "https://login.example.com#frag", "https://user:pw@login.example.com"}) {
            assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, bad, "c", "SECRET_X", null, false, EXECUTOR), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("createNew refuses missing ids and executor, a bad client id, a secret reference that is not a variable name, and bad scopes")
    void createNewGuards() {
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(null, org, "https://i.example", "c", "SECRET_X", null, false, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, null, "https://i.example", "c", "SECRET_X", null, false, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", null, false, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", null, false, " "));
        for (String clientId : new String[]{null, "", " ", "c".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, "https://i.example", clientId, "SECRET_X", null, false, EXECUTOR));
        }
        for (String reference : new String[]{null, "", "secret", "1SECRET", "S", "MY-SECRET", "a-real-looking-secret-value", "S".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, "https://i.example", "c", reference, null, false, EXECUTOR), String.valueOf(reference));
        }
        for (String scopes : new String[]{"email profile", "openid  email", "openid;email", "openid " + "x".repeat(200)}) {
            assertThrows(IllegalArgumentException.class, () -> IdentityProvider.createNew(id, org, "https://i.example", "c", "SECRET_X", scopes, false, EXECUTOR), scopes);
        }
    }

    @Test
    @DisplayName("settings are editable while DISABLED, recorded as UPDATED, and refused while ACTIVE")
    void updateSettings() {
        IdentityProvider provider = provider();

        AuditEntry entry = provider.updateSettings("https://other.example", "c2", "OTHER_SECRET", "openid email", true, EXECUTOR);

        assertEquals("https://other.example", provider.getIssuer());
        assertEquals("c2", provider.getClientId());
        assertEquals("OTHER_SECRET", provider.getClientSecretRef());
        assertEquals("openid email", provider.getScopes());
        assertTrue(provider.isAutoProvision());
        assertEquals("UPDATED", entry.action());
        assertEquals(2, provider.getAuditTrail().size());
        assertThrows(IllegalArgumentException.class, () -> provider.updateSettings("https://other.example", "c2", "OTHER_SECRET", null, false, null));
        assertThrows(IllegalArgumentException.class, () -> provider.updateSettings("ftp://nope.example", "c2", "OTHER_SECRET", null, false, EXECUTOR));

        provider.enable(EXECUTOR);
        assertThrows(InvalidIdentityProviderStatusException.class, () -> provider.updateSettings("https://other.example", "c2", "OTHER_SECRET", null, false, EXECUTOR));
    }

    @Test
    @DisplayName("the lifecycle is DISABLED <-> ACTIVE and nothing else")
    void lifecycle() {
        IdentityProvider provider = provider();
        assertThrows(InvalidIdentityProviderStatusException.class, () -> provider.disable(EXECUTOR));

        AuditEntry enabled = provider.enable(EXECUTOR);
        assertEquals(ProviderStatus.ACTIVE, provider.getStatus());
        assertEquals("DISABLED", enabled.fromStatus());
        assertEquals("ACTIVE", enabled.toStatus());
        assertThrows(InvalidIdentityProviderStatusException.class, () -> provider.enable(EXECUTOR));

        provider.disable(EXECUTOR);
        assertEquals(ProviderStatus.DISABLED, provider.getStatus());
        assertThrows(IllegalArgumentException.class, () -> provider.enable(null));
        assertEquals(3, provider.getAuditTrail().size());
        assertThrows(UnsupportedOperationException.class, () -> provider.getAuditTrail().clear());
    }

    @Test
    @DisplayName("reconstitute rebuilds the aggregate, tolerating a missing trail, and refuses missing fields")
    void reconstitute() {
        Instant now = Instant.now();
        IdentityProvider rebuilt = IdentityProvider.reconstitute(id, org, "https://i.example", "c", "SECRET_X", "openid", true, ProviderStatus.ACTIVE, now, now, null);
        assertTrue(rebuilt.getAuditTrail().isEmpty());
        assertEquals(now, rebuilt.getCreatedAt());
        assertEquals(now, rebuilt.getUpdatedAt());
        assertEquals(id, rebuilt.getId());
        assertEquals(org, rebuilt.getOrganisationId());
        AuditEntry entry = new AuditEntry(now, "INITIATED", EXECUTOR, null, "DISABLED", "d");
        assertEquals(1, IdentityProvider.reconstitute(id, org, "https://i.example", "c", "SECRET_X", "openid", true, ProviderStatus.ACTIVE, now, now, List.of(entry)).getAuditTrail().size());

        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(null, org, "i", "c", "S", "o", true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, null, "i", "c", "S", "o", true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, null, "c", "S", "o", true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", null, "S", "o", true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", "c", null, "o", true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", "c", "S", null, true, ProviderStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", "c", "S", "o", true, null, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", "c", "S", "o", true, ProviderStatus.ACTIVE, null, now, null));
        assertThrows(IllegalArgumentException.class, () -> IdentityProvider.reconstitute(id, org, "i", "c", "S", "o", true, ProviderStatus.ACTIVE, now, null, null));
    }
}
