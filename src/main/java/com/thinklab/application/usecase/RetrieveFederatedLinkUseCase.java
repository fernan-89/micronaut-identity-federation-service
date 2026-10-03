package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.mapper.FederationMapper;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import com.thinklab.domain.repository.FederatedLinkRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Read side of the FederatedLink aggregate (BIAN Behavior Qualifier: {@code link/retrieve}), always scoped to one tenant. */
@Singleton
public class RetrieveFederatedLinkUseCase {

    private final LinkLookup linkLookup;
    private final FederatedLinkRepository repository;

    public RetrieveFederatedLinkUseCase(LinkLookup linkLookup, FederatedLinkRepository repository) {
        this.linkLookup = linkLookup;
        this.repository = repository;
    }

    public Mono<FederatedLinkResponse> byId(UUID id, UUID organisationId) {
        return linkLookup.owned(id, organisationId).map(FederationMapper::toResponse);
    }

    public Flux<FederatedLinkResponse> all(UUID organisationId, LinkStatus status) {
        return repository.findAllByOrganisationId(organisationId, status).map(FederationMapper::toResponse);
    }
}
