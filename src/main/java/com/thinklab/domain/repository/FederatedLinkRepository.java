package com.thinklab.domain.repository;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for FederatedLink persistence. Every change is one atomic update that also appends its {@link AuditEntry}; there is
 * no physical delete.
 */
public interface FederatedLinkRepository {

    /** Fails with {@code DuplicateFederatedLinkException} when the identity or the user already has an ACTIVE link. */
    Mono<FederatedLink> create(FederatedLink link);

    Mono<FederatedLink> findById(UUID id);

    /** The ACTIVE link for this external identity, if any. */
    Mono<FederatedLink> findActive(UUID organisationId, String issuer, String subject);

    Flux<FederatedLink> findAllByOrganisationId(UUID organisationId, LinkStatus status);

    Mono<Void> updateStatus(UUID id, LinkStatus status, AuditEntry auditEntry);
}
