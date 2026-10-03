package com.thinklab.application.usecase;

import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.exception.ProviderNotAvailableException;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.domain.port.OidcClientPort;
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
 * Starts a sign-in (BIAN Behavior Qualifier: {@code login/initiate}): the organisation's provider must exist and be ACTIVE; a fresh
 * {@code state}, {@code nonce} and PKCE verifier are stored as a single-use transaction, and the answer is the provider's
 * authorization URL the browser is sent to. Nothing about the person is known yet.
 */
@Singleton
public class InitiateLoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateLoginUseCase.class);

    private final IdentityProviderRepository providers;
    private final LoginTransactionRepository transactions;
    private final OidcClientPort oidc;
    private final LoginTokens tokens;
    private final FederationProperties properties;
    private final Clock clock;

    @Inject
    public InitiateLoginUseCase(IdentityProviderRepository providers, LoginTransactionRepository transactions, OidcClientPort oidc,
                                LoginTokens tokens, FederationProperties properties) {
        this(providers, transactions, oidc, tokens, properties, Clock.systemUTC());
    }

    InitiateLoginUseCase(IdentityProviderRepository providers, LoginTransactionRepository transactions, OidcClientPort oidc,
                         LoginTokens tokens, FederationProperties properties, Clock clock) {
        this.providers = providers;
        this.transactions = transactions;
        this.oidc = oidc;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<String> execute(UUID organisationId) {
        log.info("[USE CASE] Starting a federated sign-in for organisation: {}", organisationId);

        return providers.findByOrganisationId(organisationId)
                .switchIfEmpty(Mono.error(new IdentityProviderNotFoundException("Organisation " + organisationId + " has no identity provider.")))
                .flatMap(provider -> {
                    if (provider.getStatus() != ProviderStatus.ACTIVE) {
                        return Mono.error(new ProviderNotAvailableException("The identity provider of this organisation is not enabled."));
                    }
                    String state = tokens.next();
                    String nonce = tokens.next();
                    String verifier = tokens.next();
                    LoginTransaction transaction = new LoginTransaction(state, nonce, verifier, organisationId, clock.instant());
                    return transactions.save(transaction)
                            .then(Mono.defer(() -> oidc.authorizationUrl(provider, properties.getRedirectUri(), state, nonce, tokens.challengeFor(verifier))));
                });
    }
}
