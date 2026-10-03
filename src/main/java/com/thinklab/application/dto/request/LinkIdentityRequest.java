package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO for {@code link/initiate}: which platform user the provider's {@code subject} (the OIDC {@code sub}) is. */
@Serdeable
public record LinkIdentityRequest(

        @NotNull(message = "User ID is required")
        UUID userId,

        @NotBlank(message = "Subject is required")
        @Size(max = 255, message = "Subject must not exceed 255 characters")
        String subject
) {}
