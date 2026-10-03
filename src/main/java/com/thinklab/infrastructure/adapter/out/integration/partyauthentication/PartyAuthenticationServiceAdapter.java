package com.thinklab.infrastructure.adapter.out.integration.partyauthentication;

import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.port.PartyAuthenticationPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Adapter for the party-authentication Service Domain: the one issuer of sessions. Every failure on the way (a user it
 * refuses, an unreachable service) is the same generic {@link FederationAuthenticationException}: a person signing in learns
 * nothing about which part of the platform said no.
 */
@Singleton
public class PartyAuthenticationServiceAdapter implements PartyAuthenticationPort {

    private static final Logger log = LoggerFactory.getLogger(PartyAuthenticationServiceAdapter.class);
    static final String EXECUTOR = "identity-federation";

    private final PartyAuthenticationApiClient apiClient;

    public PartyAuthenticationServiceAdapter(PartyAuthenticationApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<FederatedSession> openSession(UUID organisationId, UUID userId) {
        return apiClient.federated(new FederatedSessionApiRequest(organisationId, userId))
                .map(session -> new FederatedSession(session.accessToken(), session.tokenType(), session.expiresIn(), session.refreshToken(), session.refreshExpiresIn()))
                .doOnError(error -> log.warn("[INTEGRATION] party-authentication refused the federated session for organisation {}", organisationId))
                .onErrorMap(error -> new FederationAuthenticationException("The platform could not open a session for this identity."));
    }

    @Override
    public Mono<UUID> provisionViewer(UUID organisationId, String email, String fullName) {
        return apiClient.initiateUser(organisationId.toString(), EXECUTOR, new InitiateUserApiRequest(fullName, email, "VIEWER"))
                .flatMap(user -> apiClient.activateUser(user.id(), organisationId.toString(), EXECUTOR).thenReturn(user.id()))
                .doOnError(error -> log.warn("[INTEGRATION] party-authentication could not provision a user for organisation {}", organisationId))
                .onErrorMap(error -> new FederationAuthenticationException("The platform could not provision a user for this identity."));
    }

    @Serdeable
    @Introspected
    record FederatedSessionApiRequest(UUID organisationId, UUID userId) {}

    @Serdeable
    @Introspected
    record SessionApiResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}

    @Serdeable
    @Introspected
    record InitiateUserApiRequest(String fullName, String email, String role) {}

    @Serdeable
    @Introspected
    record UserApiResponse(UUID id) {}
}

/** Declarative client for party-authentication; the id maps to the configuration in application.yml. */
@Client(id = "party-authentication-service", path = "/party-authentication/v1")
interface PartyAuthenticationApiClient {

    @Post("/session/federated")
    Mono<PartyAuthenticationServiceAdapter.SessionApiResponse> federated(@Body PartyAuthenticationServiceAdapter.FederatedSessionApiRequest request);

    @Post("/initiate")
    Mono<PartyAuthenticationServiceAdapter.UserApiResponse> initiateUser(
            @Header("X-Tenant-Id") String tenantId,
            @Header("X-Executor") String executor,
            @Body PartyAuthenticationServiceAdapter.InitiateUserApiRequest request
    );

    @Put("/{id}/control/activate")
    Mono<Void> activateUser(@PathVariable UUID id, @Header("X-Tenant-Id") String tenantId, @Header("X-Executor") String executor);
}
