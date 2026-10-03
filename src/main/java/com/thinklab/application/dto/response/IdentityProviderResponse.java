package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** DTO for IdentityProvider output payload. It names the secret's variable, never a secret. */
@Serdeable
public record IdentityProviderResponse(
        UUID id,
        UUID organisationId,
        String issuer,
        String clientId,
        String clientSecretRef,
        String scopes,
        boolean autoProvision,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
