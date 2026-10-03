package com.thinklab.application.usecase;

import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Loads an identity provider on behalf of one tenant; another tenant's provider answers exactly like a missing one (404). */
@Singleton
public class ProviderLookup {

    private final IdentityProviderRepository repository;

    public ProviderLookup(IdentityProviderRepository repository) {
        this.repository = repository;
    }

    public Mono<IdentityProvider> owned(UUID id, UUID organisationId) {
        return repository.findById(id)
                .filter(provider -> provider.getOrganisationId().equals(organisationId))
                .switchIfEmpty(Mono.error(new IdentityProviderNotFoundException(id)));
    }
}
