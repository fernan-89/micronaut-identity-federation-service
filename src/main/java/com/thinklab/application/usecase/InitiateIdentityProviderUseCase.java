package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.mapper.FederationMapper;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Registers an organisation's OIDC provider (BIAN Behavior Qualifier: {@code initiate}). It starts DISABLED. The unique
 * {@code organisationId} index refuses a second provider for the same organisation.
 */
@Singleton
public class InitiateIdentityProviderUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateIdentityProviderUseCase.class);

    private final HashServicePort hashServicePort;
    private final IdentityProviderRepository repository;
    private final IssuerPolicy issuerPolicy;

    public InitiateIdentityProviderUseCase(HashServicePort hashServicePort, IdentityProviderRepository repository, IssuerPolicy issuerPolicy) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.issuerPolicy = issuerPolicy;
    }

    public Mono<IdentityProviderResponse> execute(UUID organisationId, IdentityProviderSettingsRequest request, String executor) {
        log.info("[USE CASE] Registering identity provider for organisation: {}", organisationId);

        return Mono.fromRunnable(() -> issuerPolicy.requireTrusted(request.issuer().trim()))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("identity-provider-creation")))
                .map(id -> IdentityProvider.createNew(id, organisationId, request.issuer(), request.clientId(), request.clientSecretRef(),
                        request.scopes(), Boolean.TRUE.equals(request.autoProvision()), executor))
                .flatMap(repository::create)
                .map(FederationMapper::toResponse);
    }
}
