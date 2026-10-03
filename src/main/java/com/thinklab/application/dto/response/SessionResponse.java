package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/**
 * The credentials of the session party-authentication opened for a federated sign-in, relayed as they are. The platform gateway
 * moves the refresh token into an HttpOnly cookie before it reaches the browser (gateway ADR-025): the browser's scripts never see it.
 */
@Serdeable
public record SessionResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}
