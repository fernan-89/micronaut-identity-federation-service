package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.mapper.FederationMapper;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.domain.repository.IdentityProviderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Links an external identity to a platform user (BIAN Behavior Qualifier: {@code link/initiate}). The organisation must have a
 * provider (its issuer is part of the link); the partial unique indexes refuse an identity or a user that is already linked.
 */
@Singleton
public class InitiateFederatedLinkUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateFederatedLinkUseCase.class);

    private final HashServicePort hashServicePort;
    private final IdentityProviderRepository providers;
    private final FederatedLinkRepository links;

    public InitiateFederatedLinkUseCase(HashServicePort hashServicePort, IdentityProviderRepository providers, FederatedLinkRepository links) {
        this.hashServicePort = hashServicePort;
        this.providers = providers;
        this.links = links;
    }

    public Mono<FederatedLinkResponse> execute(UUID organisationId, LinkIdentityRequest request, String executor) {
        log.info("[USE CASE] Linking an external identity to user {} in organisation: {}", request.userId(), organisationId);

        return providers.findByOrganisationId(organisationId)
                .switchIfEmpty(Mono.error(new IdentityProviderNotFoundException("Organisation " + organisationId + " has no identity provider.")))
                .flatMap(provider -> hashServicePort.generateSovereignId("federated-link-creation")
                        .map(id -> FederatedLink.createNew(id, organisationId, provider.getIssuer(), request.subject(), request.userId(), executor)))
                .flatMap(links::create)
                .map(FederationMapper::toResponse);
    }
}
