package com.thinklab.application.usecase;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.BiFunction;

/** Governs the IdentityProvider lifecycle (BIAN Behavior Qualifier: {@code control}), tenant-scoped; the aggregate refuses an illegal move (409). */
@Singleton
public class ControlIdentityProviderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlIdentityProviderUseCase.class);

    private final ProviderLookup providerLookup;
    private final IdentityProviderRepository repository;

    public ControlIdentityProviderUseCase(ProviderLookup providerLookup, IdentityProviderRepository repository) {
        this.providerLookup = providerLookup;
        this.repository = repository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor) {
        log.info("[USE CASE] Controlling identity provider lifecycle: {} for ID: {}", action, id);

        return providerLookup.owned(id, organisationId).flatMap(provider -> {
            AuditEntry entry = action.transition().apply(provider, executor);
            return repository.updateStatus(id, ProviderStatus.valueOf(entry.toStatus()), entry);
        });
    }

    public enum Action {
        ENABLE(IdentityProvider::enable),
        DISABLE(IdentityProvider::disable);

        private final BiFunction<IdentityProvider, String, AuditEntry> transition;

        Action(BiFunction<IdentityProvider, String, AuditEntry> transition) {
            this.transition = transition;
        }

        BiFunction<IdentityProvider, String, AuditEntry> transition() {
            return transition;
        }
    }
}
