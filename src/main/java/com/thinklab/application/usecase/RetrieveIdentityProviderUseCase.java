package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.mapper.FederationMapper;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Read side of the IdentityProvider aggregate (BIAN Behavior Qualifier: {@code retrieve}), always scoped to one tenant. */
@Singleton
public class RetrieveIdentityProviderUseCase {

    private final ProviderLookup providerLookup;
    private final IdentityProviderRepository repository;

    public RetrieveIdentityProviderUseCase(ProviderLookup providerLookup, IdentityProviderRepository repository) {
        this.providerLookup = providerLookup;
        this.repository = repository;
    }

    public Mono<IdentityProviderResponse> byId(UUID id, UUID organisationId) {
        return providerLookup.owned(id, organisationId).map(FederationMapper::toResponse);
    }

    public Flux<IdentityProviderResponse> all(UUID organisationId) {
        return repository.findAllByOrganisationId(organisationId).map(FederationMapper::toResponse);
    }

    public Mono<List<AuditEntryResponse>> auditLog(UUID id, UUID organisationId) {
        return providerLookup.owned(id, organisationId)
                .map(provider -> provider.getAuditTrail().stream().map(FederationMapper::toResponse).toList());
    }
}
