package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.SessionResponse;
import com.thinklab.application.mapper.FederationMapper;
import com.thinklab.domain.exception.IdentityNotLinkedException;
import com.thinklab.domain.exception.InvalidLoginStateException;
import com.thinklab.domain.exception.ProviderMisconfiguredException;
import com.thinklab.domain.exception.ProviderNotAvailableException;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.IdentityClaims;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.domain.port.ClientSecretPort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.OidcClientPort;
import com.thinklab.domain.port.PartyAuthenticationPort;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.domain.repository.IdentityProviderRepository;
import com.thinklab.domain.repository.LoginTransactionRepository;
import com.thinklab.application.config.FederationProperties;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Finishes a sign-in (BIAN Behavior Qualifier: {@code login/callback}). In order:
 * <ol>
 *   <li>the {@code state} is consumed (single use) and must be younger than the transaction TTL - otherwise the callback is a replay
 *       or a forgery (400);</li>
 *   <li>the provider must still be ACTIVE and its client secret must be resolvable (the environment variable it names);</li>
 *   <li>the code is exchanged and the ID token validated by the OIDC port (signature, issuer, audience, expiry, nonce);</li>
 *   <li>the identity must be LINKED to a user (ADR-031: pre-provisioned). Only when the organisation turned automatic provisioning
 *       on, and the provider vouches for the email, is a first-time identity given a new VIEWER and a link;</li>
 *   <li>party-authentication opens the session through its own issuer.</li>
 * </ol>
 * Nothing the provider said is stored beyond the link's {@code subject}; the email and name live only for this call.
 */
@Singleton
public class CompleteLoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompleteLoginUseCase.class);
    static final String EXECUTOR = "identity-federation";

    private final LoginTransactionRepository transactions;
    private final IdentityProviderRepository providers;
    private final FederatedLinkRepository links;
    private final ClientSecretPort secrets;
    private final OidcClientPort oidc;
    private final PartyAuthenticationPort party;
    private final HashServicePort hashServicePort;
    private final FederationProperties properties;
    private final Clock clock;

    @Inject
    public CompleteLoginUseCase(LoginTransactionRepository transactions, IdentityProviderRepository providers, FederatedLinkRepository links,
                                ClientSecretPort secrets, OidcClientPort oidc, PartyAuthenticationPort party, HashServicePort hashServicePort,
                                FederationProperties properties) {
        this(transactions, providers, links, secrets, oidc, party, hashServicePort, properties, Clock.systemUTC());
    }

    CompleteLoginUseCase(LoginTransactionRepository transactions, IdentityProviderRepository providers, FederatedLinkRepository links,
                         ClientSecretPort secrets, OidcClientPort oidc, PartyAuthenticationPort party, HashServicePort hashServicePort,
                         FederationProperties properties, Clock clock) {
        this.transactions = transactions;
        this.providers = providers;
        this.links = links;
        this.secrets = secrets;
        this.oidc = oidc;
        this.party = party;
        this.hashServicePort = hashServicePort;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<SessionResponse> execute(String code, String state) {
        return transactions.consume(state)
                .switchIfEmpty(Mono.error(new InvalidLoginStateException("The sign-in state is unknown or was already used.")))
                .flatMap(transaction -> {
                    if (transaction.createdAt().plusSeconds(properties.getTransactionTtlSeconds()).isBefore(clock.instant())) {
                        return Mono.error(new InvalidLoginStateException("The sign-in took too long; start again."));
                    }
                    return providers.findByOrganisationId(transaction.organisationId())
                            .filter(provider -> provider.getStatus() == ProviderStatus.ACTIVE)
                            .switchIfEmpty(Mono.error(new ProviderNotAvailableException("The identity provider of this organisation is not enabled.")))
                            .flatMap(provider -> authenticate(provider, transaction, code));
                })
                .doOnError(error -> log.warn("[USE CASE] Federated sign-in refused: {}", error.getClass().getSimpleName()));
    }

    private Mono<SessionResponse> authenticate(IdentityProvider provider, LoginTransaction transaction, String code) {
        String secret = secrets.resolve(provider.getClientSecretRef()).orElse(null);
        if (secret == null) {
            return Mono.error(new ProviderMisconfiguredException("The client secret of this identity provider is not configured on the server."));
        }
        return oidc.authenticate(provider, secret, properties.getRedirectUri(), code, transaction.codeVerifier(), transaction.nonce())
                .flatMap(claims -> userFor(provider, claims))
                .flatMap(userId -> party.openSession(provider.getOrganisationId(), userId))
                .map(FederationMapper::toResponse);
    }

    private Mono<UUID> userFor(IdentityProvider provider, IdentityClaims claims) {
        return links.findActive(provider.getOrganisationId(), provider.getIssuer(), claims.subject())
                .map(FederatedLink::getUserId)
                .switchIfEmpty(Mono.defer(() -> provision(provider, claims)));
    }

    private Mono<UUID> provision(IdentityProvider provider, IdentityClaims claims) {
        if (!provider.isAutoProvision() || claims.email() == null || !claims.emailVerified()) {
            return Mono.error(new IdentityNotLinkedException("This identity is not linked to a user of the organisation."));
        }
        String name = claims.name() == null || claims.name().isBlank() ? claims.email().split("@")[0] : claims.name();
        return party.provisionViewer(provider.getOrganisationId(), claims.email(), name)
                .flatMap(userId -> hashServicePort.generateSovereignId("federated-link-creation")
                        .map(id -> FederatedLink.createNew(id, provider.getOrganisationId(), provider.getIssuer(), claims.subject(), userId, EXECUTOR))
                        .flatMap(links::create)
                        .map(FederatedLink::getUserId));
    }
}
