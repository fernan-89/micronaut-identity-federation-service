package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.infrastructure.adapter.out.persistence.entity.FederatedLinkDocument.FederatedLinkPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.IdentityProviderDocument.IdentityProviderPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentsTest {

    @Test
    @DisplayName("an IdentityProvider survives the document round trip, ledger included")
    void providerRoundTrip() {
        IdentityProvider provider = IdentityProvider.createNew(UUID.randomUUID(), UUID.randomUUID(), "https://i.example", "client", "OIDC_SECRET",
                "openid email", true, "admin");
        provider.enable("admin");

        IdentityProviderDocument document = IdentityProviderPersistenceMapper.toDocument(provider);
        assertEquals("ACTIVE", document.getStatus());
        assertEquals("OIDC_SECRET", document.getClientSecretRef());
        assertEquals(2, document.getAuditTrail().size());
        IdentityProvider back = IdentityProviderPersistenceMapper.toDomain(document);

        assertEquals(provider.getId(), back.getId());
        assertEquals(provider.getOrganisationId(), back.getOrganisationId());
        assertEquals("https://i.example", back.getIssuer());
        assertEquals("client", back.getClientId());
        assertEquals("OIDC_SECRET", back.getClientSecretRef());
        assertEquals("openid email", back.getScopes());
        assertTrue(back.isAutoProvision());
        assertEquals(provider.getStatus(), back.getStatus());
        assertEquals(provider.getAuditTrail(), back.getAuditTrail());
    }

    @Test
    @DisplayName("a FederatedLink survives the document round trip, ledger included")
    void linkRoundTrip() {
        FederatedLink link = FederatedLink.createNew(UUID.randomUUID(), UUID.randomUUID(), "https://i.example", "sub-1", UUID.randomUUID(), "admin");
        link.revoke("admin");

        FederatedLinkDocument document = FederatedLinkPersistenceMapper.toDocument(link);
        assertEquals("REVOKED", document.getStatus());
        FederatedLink back = FederatedLinkPersistenceMapper.toDomain(document);

        assertEquals(link.getId(), back.getId());
        assertEquals(link.getOrganisationId(), back.getOrganisationId());
        assertEquals("sub-1", back.getSubject());
        assertEquals("https://i.example", back.getIssuer());
        assertEquals(link.getUserId(), back.getUserId());
        assertEquals(link.getStatus(), back.getStatus());
        assertEquals(link.getAuditTrail(), back.getAuditTrail());
    }

    @Test
    @DisplayName("a LoginTransaction keeps its state as the document id and survives the round trip; an audit entry survives its document form")
    void transactionAndEntryRoundTrip() {
        LoginTransaction transaction = new LoginTransaction("state-1", "nonce-1", "verifier-1", UUID.randomUUID(), Instant.parse("2026-10-03T12:00:00Z"));

        LoginTransactionDocument document = LoginTransactionDocument.fromDomain(transaction);
        assertEquals("state-1", document.getState());
        assertEquals(transaction, document.toDomain());

        AuditEntry entry = new AuditEntry(Instant.parse("2026-10-03T12:00:00Z"), "INITIATED", "admin", null, "DISABLED", "d");
        assertEquals(entry, AuditEntryDocument.fromDomain(entry).toDomain());
    }

    @Test
    @DisplayName("the documents expose every field through plain accessors, as the BSON codec needs")
    void accessors() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        IdentityProviderDocument provider = new IdentityProviderDocument();
        provider.setId(id);
        provider.setOrganisationId(id);
        provider.setIssuer("i");
        provider.setClientId("c");
        provider.setClientSecretRef("S");
        provider.setScopes("openid");
        provider.setAutoProvision(true);
        provider.setStatus("ACTIVE");
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
        provider.setAuditTrail(List.of());
        assertEquals(id, provider.getId());
        assertEquals(id, provider.getOrganisationId());
        assertEquals("i", provider.getIssuer());
        assertEquals("c", provider.getClientId());
        assertEquals("openid", provider.getScopes());
        assertEquals(now, provider.getCreatedAt());
        assertEquals(now, provider.getUpdatedAt());
        assertEquals(0, provider.getAuditTrail().size());

        FederatedLinkDocument link = new FederatedLinkDocument();
        link.setId(id);
        link.setOrganisationId(id);
        link.setIssuer("i");
        link.setSubject("s");
        link.setUserId(id);
        link.setStatus("ACTIVE");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        link.setAuditTrail(List.of());
        assertEquals(id, link.getId());
        assertEquals(id, link.getOrganisationId());
        assertEquals("i", link.getIssuer());
        assertEquals("s", link.getSubject());
        assertEquals(id, link.getUserId());
        assertEquals(now, link.getCreatedAt());
        assertEquals(now, link.getUpdatedAt());
        assertEquals(0, link.getAuditTrail().size());

        LoginTransactionDocument transaction = new LoginTransactionDocument();
        transaction.setState("s");
        transaction.setNonce("n");
        transaction.setCodeVerifier("v");
        transaction.setOrganisationId(id);
        transaction.setCreatedAt(now);
        assertEquals("n", transaction.getNonce());
        assertEquals("v", transaction.getCodeVerifier());
        assertEquals(id, transaction.getOrganisationId());
        assertEquals(now, transaction.getCreatedAt());
    }

    @Test
    @DisplayName("the persistence mappers are non-instantiable utility classes")
    void utilityClasses() throws Exception {
        for (Class<?> type : new Class<?>[]{IdentityProviderPersistenceMapper.class, FederatedLinkPersistenceMapper.class}) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        }
    }
}
