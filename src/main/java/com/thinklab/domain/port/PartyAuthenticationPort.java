package com.thinklab.domain.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port to the party-authentication Service Domain: the one issuer of sessions (ADR-022 of that service). */
public interface PartyAuthenticationPort {

    /** What a federated sign-in ends with: the credentials of the session party-authentication opened. */
    record FederatedSession(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}

    /** Opens a session for an ACTIVE user. Fails with {@code FederationAuthenticationException} when the user is refused. */
    Mono<FederatedSession> openSession(UUID organisationId, UUID userId);

    /** Creates and activates a VIEWER (the least privileged role) for a first-time identity, and returns its id. */
    Mono<UUID> provisionViewer(UUID organisationId, String email, String fullName);
}
