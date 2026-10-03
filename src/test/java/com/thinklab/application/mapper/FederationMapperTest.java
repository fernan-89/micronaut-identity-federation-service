package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.dto.response.SessionResponse;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.port.PartyAuthenticationPort.FederatedSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederationMapperTest {

    @Test
    @DisplayName("a provider, a link, an audit entry and a session map field for field; a provider names its secret variable and holds no secret")
    void mapsEverything() {
        UUID id = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        IdentityProvider provider = IdentityProvider.createNew(id, org, "https://i.example", "client", "OIDC_SECRET", "openid email", true, "admin");
        FederatedLink link = FederatedLink.createNew(id, org, "https://i.example", "sub-1", user, "admin");

        IdentityProviderResponse providerResponse = FederationMapper.toResponse(provider);
        assertEquals(id, providerResponse.id());
        assertEquals("https://i.example", providerResponse.issuer());
        assertEquals("client", providerResponse.clientId());
        assertEquals("OIDC_SECRET", providerResponse.clientSecretRef());
        assertEquals("openid email", providerResponse.scopes());
        assertEquals(true, providerResponse.autoProvision());
        assertEquals("DISABLED", providerResponse.status());

        FederatedLinkResponse linkResponse = FederationMapper.toResponse(link);
        assertEquals("sub-1", linkResponse.subject());
        assertEquals(user, linkResponse.userId());
        assertEquals("ACTIVE", linkResponse.status());

        assertEquals("INITIATED", FederationMapper.toResponse(new AuditEntry(Instant.now(), "INITIATED", "admin", null, "DISABLED", "d")).action());

        SessionResponse session = FederationMapper.toResponse(new FederatedSession("a", "Bearer", 600, "r", 3600));
        assertEquals("a", session.accessToken());
        assertEquals("Bearer", session.tokenType());
        assertEquals(600, session.expiresIn());
        assertEquals("r", session.refreshToken());
        assertEquals(3600, session.refreshExpiresIn());
    }

    @Test
    @DisplayName("the mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<FederationMapper> constructor = FederationMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
