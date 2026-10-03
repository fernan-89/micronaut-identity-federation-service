package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Replaces a DISABLED provider's settings (BIAN Behavior Qualifier: {@code update}), tenant-scoped; the aggregate refuses an ACTIVE one. */
@Singleton
public class UpdateIdentityProviderUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateIdentityProviderUseCase.class);

    private final ProviderLookup providerLookup;
    private final IdentityProviderRepository repository;
    private final IssuerPolicy issuerPolicy;

    public UpdateIdentityProviderUseCase(ProviderLookup providerLookup, IdentityProviderRepository repository, IssuerPolicy issuerPolicy) {
        this.providerLookup = providerLookup;
        this.repository = repository;
        this.issuerPolicy = issuerPolicy;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, IdentityProviderSettingsRequest request, String executor) {
        log.info("[USE CASE] Updating identity provider ID: {}", id);

        return providerLookup.owned(id, organisationId).flatMap(provider -> {
            issuerPolicy.requireTrusted(request.issuer().trim());
            AuditEntry entry = provider.updateSettings(request.issuer(), request.clientId(), request.clientSecretRef(), request.scopes(),
                    Boolean.TRUE.equals(request.autoProvision()), executor);
            return repository.updateSettings(id, provider.getIssuer(), provider.getClientId(), provider.getClientSecretRef(),
                    provider.getScopes(), provider.isAutoProvision(), entry);
        });
    }
}
