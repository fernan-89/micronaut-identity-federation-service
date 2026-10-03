package com.thinklab.application.usecase;

import com.thinklab.domain.exception.FederatedLinkNotFoundException;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.repository.FederatedLinkRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Loads a federated link on behalf of one tenant; another tenant's link answers exactly like a missing one (404). */
@Singleton
public class LinkLookup {

    private final FederatedLinkRepository repository;

    public LinkLookup(FederatedLinkRepository repository) {
        this.repository = repository;
    }

    public Mono<FederatedLink> owned(UUID id, UUID organisationId) {
        return repository.findById(id)
                .filter(link -> link.getOrganisationId().equals(organisationId))
                .switchIfEmpty(Mono.error(new FederatedLinkNotFoundException("Federated link " + id + " could not be found.")));
    }
}
