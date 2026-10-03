package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.dto.response.SessionResponse;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.port.PartyAuthenticationPort.FederatedSession;

/** Static factory mapper for the DTOs and Domain Entities. Enforces the DTO Isolation Pattern. */
public final class FederationMapper {

    private FederationMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static IdentityProviderResponse toResponse(IdentityProvider provider) {
        return new IdentityProviderResponse(provider.getId(), provider.getOrganisationId(), provider.getIssuer(), provider.getClientId(),
                provider.getClientSecretRef(), provider.getScopes(), provider.isAutoProvision(), provider.getStatus().name(),
                provider.getCreatedAt(), provider.getUpdatedAt());
    }

    public static FederatedLinkResponse toResponse(FederatedLink link) {
        return new FederatedLinkResponse(link.getId(), link.getOrganisationId(), link.getIssuer(), link.getSubject(), link.getUserId(),
                link.getStatus().name(), link.getCreatedAt(), link.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(AuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus(), entry.toStatus(), entry.detail());
    }

    public static SessionResponse toResponse(FederatedSession session) {
        return new SessionResponse(session.accessToken(), session.tokenType(), session.expiresIn(), session.refreshToken(), session.refreshExpiresIn());
    }
}
