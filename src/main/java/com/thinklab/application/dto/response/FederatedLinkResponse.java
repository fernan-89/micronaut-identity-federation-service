package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** DTO for FederatedLink output payload. */
@Serdeable
public record FederatedLinkResponse(
        UUID id,
        UUID organisationId,
        String issuer,
        String subject,
        UUID userId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
