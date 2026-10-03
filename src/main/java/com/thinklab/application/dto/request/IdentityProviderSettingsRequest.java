package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for registering ({@code initiate}) or replacing ({@code update}) an organisation's OIDC provider. organisationId travels via
 * the {@code X-Tenant-Id} header. {@code clientSecretRef} is the NAME of the environment variable that holds the client secret -
 * never the secret itself. {@code scopes} defaults to {@code openid email profile}; {@code autoProvision} defaults to false.
 */
@Serdeable
public record IdentityProviderSettingsRequest(

        @NotBlank(message = "Issuer is required")
        @Size(max = 300, message = "Issuer must not exceed 300 characters")
        String issuer,

        @NotBlank(message = "Client ID is required")
        @Size(max = 200, message = "Client ID must not exceed 200 characters")
        String clientId,

        @NotBlank(message = "Client secret reference is required")
        @Size(max = 64, message = "Client secret reference must not exceed 64 characters")
        String clientSecretRef,

        @Nullable
        @Size(max = 200, message = "Scopes must not exceed 200 characters")
        String scopes,

        @Nullable
        Boolean autoProvision
) {}
