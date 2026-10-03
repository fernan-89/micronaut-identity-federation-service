package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.usecase.ControlFederatedLinkUseCase;
import com.thinklab.application.usecase.ControlIdentityProviderUseCase;
import com.thinklab.application.usecase.InitiateFederatedLinkUseCase;
import com.thinklab.application.usecase.InitiateIdentityProviderUseCase;
import com.thinklab.application.usecase.RetrieveFederatedLinkUseCase;
import com.thinklab.application.usecase.RetrieveIdentityProviderUseCase;
import com.thinklab.domain.exception.DuplicateFederatedLinkException;
import com.thinklab.domain.exception.DuplicateIdentityProviderException;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.domain.repository.LoginTransactionRepository;
import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The federation domain against a real MongoDB, proving what no mock can: one provider per organisation, an external identity maps to
 * one user and a user to one identity while ACTIVE (and a REVOKED link frees both), a started sign-in is consumed exactly once even when
 * raced, and the TTL index that expires an abandoned sign-in exists (ADR-031).
 *
 * <p>{@code packages = "com.thinklab"}: otherwise Micronaut Data MongoDB stops mapping {@code @Id} to {@code _id} for entities outside
 * the test's own package.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FederationPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_identity_federation_it";
    private static final String EXECUTOR = "admin";

    /** The hash service is another process; federation only needs a fresh UUID from it. */
    @Singleton
    @Replaces(HashServiceAdapter.class)
    static class FixedHashService implements HashServicePort {
        @Override
        public Mono<UUID> generateSovereignId(String purpose) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<String> hashSensitiveData(String rawData) {
            return Mono.just(rawData);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject InitiateIdentityProviderUseCase initiateProvider;
    @Inject ControlIdentityProviderUseCase controlProvider;
    @Inject RetrieveIdentityProviderUseCase retrieveProvider;
    @Inject InitiateFederatedLinkUseCase initiateLink;
    @Inject ControlFederatedLinkUseCase controlLink;
    @Inject RetrieveFederatedLinkUseCase retrieveLink;
    @Inject FederatedLinkRepository links;
    @Inject LoginTransactionRepository transactions;
    @Inject MongoClient mongoClient;

    private IdentityProviderResponse register(UUID org) {
        return initiateProvider.execute(org, new IdentityProviderSettingsRequest("https://login.example.com", "client", "OIDC_SECRET", null, null), EXECUTOR).block();
    }

    @Test
    @DisplayName("a provider round-trips with its audit trail and its secret NAME only; a second provider for the organisation is refused, also concurrently")
    void oneProviderPerOrganisation() {
        UUID org = UUID.randomUUID();
        IdentityProviderResponse created = register(org);
        controlProvider.execute(created.id(), org, ControlIdentityProviderUseCase.Action.ENABLE, EXECUTOR).block();

        IdentityProviderResponse stored = retrieveProvider.byId(created.id(), org).block();
        assertEquals("ACTIVE", stored.status());
        assertEquals("OIDC_SECRET", stored.clientSecretRef());
        assertEquals(2, retrieveProvider.auditLog(created.id(), org).block().size());
        assertThrows(DuplicateIdentityProviderException.class, () -> register(org));

        UUID racing = UUID.randomUUID();
        List<Object> outcomes = Flux.range(0, 6)
                .flatMap(i -> initiateProvider.execute(racing, new IdentityProviderSettingsRequest("https://login.example.com", "client", "OIDC_SECRET", null, null), EXECUTOR)
                        .cast(Object.class).onErrorResume(e -> Mono.just(e)), 6)
                .collectList().block();
        assertEquals(1, outcomes.stream().filter(o -> o instanceof IdentityProviderResponse).count(), outcomes.toString());
        assertEquals(5, outcomes.stream().filter(o -> o instanceof DuplicateIdentityProviderException).count(), outcomes.toString());
    }

    @Test
    @DisplayName("an identity maps to one user and a user to one identity while ACTIVE; revoking frees both and keeps the history")
    void activeLinksAreUnique() {
        UUID org = UUID.randomUUID();
        register(org);
        UUID user = UUID.randomUUID();

        FederatedLinkResponse first = initiateLink.execute(org, new LinkIdentityRequest(user, "sub-1"), EXECUTOR).block();
        assertEquals("ACTIVE", first.status());
        assertThrows(DuplicateFederatedLinkException.class, () -> initiateLink.execute(org, new LinkIdentityRequest(UUID.randomUUID(), "sub-1"), EXECUTOR).block());
        assertThrows(DuplicateFederatedLinkException.class, () -> initiateLink.execute(org, new LinkIdentityRequest(user, "sub-2"), EXECUTOR).block());

        FederatedLink active = links.findActive(org, "https://login.example.com", "sub-1").block();
        assertEquals(user, active.getUserId());

        controlLink.revoke(first.id(), org, EXECUTOR).block();
        assertFalse(links.findActive(org, "https://login.example.com", "sub-1").blockOptional().isPresent());
        FederatedLinkResponse second = initiateLink.execute(org, new LinkIdentityRequest(user, "sub-1"), EXECUTOR).block();
        assertEquals("ACTIVE", second.status());
        assertEquals(2, retrieveLink.all(org, null).collectList().block().size());
        assertEquals(1, retrieveLink.all(org, com.thinklab.domain.model.FederatedLink.LinkStatus.REVOKED).collectList().block().size());
    }

    @Test
    @DisplayName("another tenant's identity is a separate one: the same subject links independently, and a foreign tenant cannot read or revoke a link")
    void tenantIsolation() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        register(orgA);
        register(orgB);

        FederatedLinkResponse a = initiateLink.execute(orgA, new LinkIdentityRequest(UUID.randomUUID(), "shared-sub"), EXECUTOR).block();
        initiateLink.execute(orgB, new LinkIdentityRequest(UUID.randomUUID(), "shared-sub"), EXECUTOR).block();

        assertThrows(com.thinklab.domain.exception.FederatedLinkNotFoundException.class, () -> retrieveLink.byId(a.id(), orgB).block());
        assertThrows(com.thinklab.domain.exception.FederatedLinkNotFoundException.class, () -> controlLink.revoke(a.id(), orgB, EXECUTOR).block());
        assertEquals("ACTIVE", retrieveLink.byId(a.id(), orgA).block().status());
    }

    @Test
    @DisplayName("a started sign-in is consumed exactly once, even when the callback is raced; an unknown state is empty")
    void transactionIsSingleUse() {
        LoginTransaction transaction = new LoginTransaction("state-" + UUID.randomUUID(), "nonce", "verifier", UUID.randomUUID(), Instant.now());
        transactions.save(transaction).block();

        List<LoginTransaction> consumed = Flux.range(0, 8).flatMap(i -> transactions.consume(transaction.state()), 8).collectList().block();

        assertEquals(1, consumed.size());
        assertEquals(transaction.nonce(), consumed.get(0).nonce());
        assertEquals(transaction.codeVerifier(), consumed.get(0).codeVerifier());
        assertFalse(transactions.consume(transaction.state()).blockOptional().isPresent());
        assertFalse(transactions.consume("never-issued").blockOptional().isPresent());
    }

    @Test
    @DisplayName("the indexes the rules lean on exist, including the TTL that expires an abandoned sign-in")
    void indexesExist() {
        List<String> transactionIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("login_transactions").listIndexes())
                .map(index -> index.getString("name")).collectList().block();
        List<Document> linkIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("federated_links").listIndexes()).collectList().block();

        assertTrue(transactionIndexes.contains("createdAt_1_ttl"), transactionIndexes.toString());
        assertTrue(linkIndexes.stream().anyMatch(i -> "organisationId_1_issuer_1_subject_1_active".equals(i.getString("name")) && Boolean.TRUE.equals(i.getBoolean("unique"))));
        assertTrue(linkIndexes.stream().anyMatch(i -> "organisationId_1_issuer_1_userId_1_active".equals(i.getString("name")) && i.get("partialFilterExpression") != null));
    }
}
