package com.thinklab.application.usecase;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import com.thinklab.domain.repository.FederatedLinkRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Revokes a link (BIAN Behavior Qualifier: {@code link/control/revoke}), tenant-scoped: the person can no longer sign in through the provider. */
@Singleton
public class ControlFederatedLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlFederatedLinkUseCase.class);

    private final LinkLookup linkLookup;
    private final FederatedLinkRepository repository;

    public ControlFederatedLinkUseCase(LinkLookup linkLookup, FederatedLinkRepository repository) {
        this.linkLookup = linkLookup;
        this.repository = repository;
    }

    public Mono<Void> revoke(UUID id, UUID organisationId, String executor) {
        log.info("[USE CASE] Revoking federated link ID: {}", id);

        return linkLookup.owned(id, organisationId).flatMap(link -> {
            AuditEntry entry = link.revoke(executor);
            return repository.updateStatus(id, LinkStatus.REVOKED, entry);
        });
    }
}
