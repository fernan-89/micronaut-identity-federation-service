package com.thinklab.domain.repository;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for IdentityProvider persistence. Every change is one atomic update that also appends its {@link AuditEntry}
 * (ADR-002); there is no physical delete.
 */
public interface IdentityProviderRepository {

    /** Fails with {@code DuplicateIdentityProviderException} when the organisation already has a provider. */
    Mono<IdentityProvider> create(IdentityProvider provider);

    Mono<IdentityProvider> findById(UUID id);

    Mono<IdentityProvider> findByOrganisationId(UUID organisationId);

    Flux<IdentityProvider> findAllByOrganisationId(UUID organisationId);

    Mono<Void> updateSettings(UUID id, String issuer, String clientId, String clientSecretRef, String scopes, boolean autoProvision, AuditEntry auditEntry);

    Mono<Void> updateStatus(UUID id, ProviderStatus status, AuditEntry auditEntry);
}
