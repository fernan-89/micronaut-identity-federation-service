package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.domain.exception.DuplicateFederatedLinkException;
import com.thinklab.domain.exception.DuplicateIdentityProviderException;
import com.thinklab.domain.exception.FederatedLinkNotFoundException;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.exception.InvalidIdentityProviderStatusException;
import com.thinklab.domain.exception.InvalidLinkStatusException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.domain.repository.IdentityProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederationAdminUseCasesTest {

    private static final String EXECUTOR = "admin";

    @Mock private IdentityProviderRepository providers;
    @Mock private FederatedLinkRepository links;
    @Mock private HashServicePort hashServicePort;

    private final UUID id = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private ProviderLookup providerLookup;
    private LinkLookup linkLookup;
    private final IssuerPolicy policy = new IssuerPolicy(new com.thinklab.application.config.FederationProperties());

    @BeforeEach
    void setUp() {
        providerLookup = new ProviderLookup(providers);
        linkLookup = new LinkLookup(links);
    }

    private IdentityProvider provider() {
        return IdentityProvider.createNew(id, tenant, "https://i.example", "client", "SECRET_X", null, false, EXECUTOR);
    }

    private FederatedLink link() {
        return FederatedLink.createNew(id, tenant, "https://i.example", "sub-1", user, EXECUTOR);
    }

    private IdentityProviderSettingsRequest settings() {
        return new IdentityProviderSettingsRequest("https://i2.example", "client2", "OTHER_SECRET", null, true);
    }

    @Test
    @DisplayName("lookup answers a foreign tenant's provider or link exactly like a missing one")
    void lookups() {
        when(providers.findById(id)).thenReturn(Mono.just(provider()));
        when(links.findById(id)).thenReturn(Mono.just(link()));

        StepVerifier.create(providerLookup.owned(id, tenant)).expectNextCount(1).verifyComplete();
        StepVerifier.create(providerLookup.owned(id, UUID.randomUUID())).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(linkLookup.owned(id, tenant)).expectNextCount(1).verifyComplete();
        StepVerifier.create(linkLookup.owned(id, UUID.randomUUID())).expectError(FederatedLinkNotFoundException.class).verify();
        when(providers.findById(id)).thenReturn(Mono.empty());
        when(links.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(providerLookup.owned(id, tenant)).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(linkLookup.owned(id, tenant)).expectError(FederatedLinkNotFoundException.class).verify();
    }

    @Test
    @DisplayName("initiate registers a DISABLED provider; autoProvision defaults to false; a duplicate and bad settings are refused")
    void initiateProvider() {
        InitiateIdentityProviderUseCase useCase = new InitiateIdentityProviderUseCase(hashServicePort, providers, policy);
        when(hashServicePort.generateSovereignId("identity-provider-creation")).thenReturn(Mono.just(id));
        when(providers.create(any(IdentityProvider.class))).thenAnswer(i -> Mono.just(i.getArgument(0))).thenReturn(Mono.error(new DuplicateIdentityProviderException("dup")));

        StepVerifier.create(useCase.execute(tenant, new IdentityProviderSettingsRequest("https://i.example", "c", "SECRET_X", null, null), EXECUTOR)).assertNext(r -> {
            assertEquals("DISABLED", r.status());
            assertEquals(false, r.autoProvision());
            assertEquals("SECRET_X", r.clientSecretRef());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, new IdentityProviderSettingsRequest("https://i.example", "c", "SECRET_X", null, true), EXECUTOR))
                .expectError(DuplicateIdentityProviderException.class).verify();
        StepVerifier.create(useCase.execute(tenant, new IdentityProviderSettingsRequest("http://insecure.example", "c", "SECRET_X", null, false), EXECUTOR))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("update rewrites a DISABLED provider; an ACTIVE one, a foreign tenant and a missing one are refused before any write")
    void updateProvider() {
        UpdateIdentityProviderUseCase useCase = new UpdateIdentityProviderUseCase(providerLookup, providers, policy);
        IdentityProvider active = provider();
        active.enable(EXECUTOR);
        when(providers.findById(id)).thenReturn(Mono.just(provider())).thenReturn(Mono.just(active)).thenReturn(Mono.just(provider()));
        when(providers.updateSettings(eq(id), any(), any(), any(), any(), anyBoolean(), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(id, tenant, settings(), EXECUTOR)).verifyComplete();
        verify(providers).updateSettings(eq(id), eq("https://i2.example"), eq("client2"), eq("OTHER_SECRET"), eq("openid email profile"), eq(true), any(AuditEntry.class));
        StepVerifier.create(useCase.execute(id, tenant, settings(), EXECUTOR)).expectError(InvalidIdentityProviderStatusException.class).verify();
        StepVerifier.create(useCase.execute(id, UUID.randomUUID(), settings(), EXECUTOR)).expectError(IdentityProviderNotFoundException.class).verify();
        verify(providers, org.mockito.Mockito.times(1)).updateSettings(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("update refuses a plain-http issuer before touching anything")
    void updateRefusesInsecureIssuer() {
        UpdateIdentityProviderUseCase useCase = new UpdateIdentityProviderUseCase(providerLookup, providers, policy);
        when(providers.findById(id)).thenReturn(Mono.just(provider()));

        StepVerifier.create(useCase.execute(id, tenant, new IdentityProviderSettingsRequest("http://insecure.example", "c", "SECRET_X", null, false), EXECUTOR))
                .expectError(IllegalArgumentException.class).verify();
        verify(providers, never()).updateSettings(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("control enables then disables, persisting the target status with its entry; an illegal move is refused")
    void controlProvider() {
        ControlIdentityProviderUseCase useCase = new ControlIdentityProviderUseCase(providerLookup, providers);
        IdentityProvider active = provider();
        active.enable(EXECUTOR);
        when(providers.findById(id)).thenReturn(Mono.just(provider())).thenReturn(Mono.just(active)).thenReturn(Mono.just(provider()));
        when(providers.updateStatus(eq(id), any(ProviderStatus.class), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(id, tenant, ControlIdentityProviderUseCase.Action.ENABLE, EXECUTOR)).verifyComplete();
        verify(providers).updateStatus(eq(id), eq(ProviderStatus.ACTIVE), any(AuditEntry.class));
        StepVerifier.create(useCase.execute(id, tenant, ControlIdentityProviderUseCase.Action.DISABLE, EXECUTOR)).verifyComplete();
        verify(providers).updateStatus(eq(id), eq(ProviderStatus.DISABLED), any(AuditEntry.class));
        StepVerifier.create(useCase.execute(id, tenant, ControlIdentityProviderUseCase.Action.DISABLE, EXECUTOR)).expectError(InvalidIdentityProviderStatusException.class).verify();
    }

    @Test
    @DisplayName("retrieve maps by id, the tenant's providers and the audit log, scoped to the tenant")
    void retrieveProvider() {
        RetrieveIdentityProviderUseCase useCase = new RetrieveIdentityProviderUseCase(providerLookup, providers);
        when(providers.findById(id)).thenReturn(Mono.just(provider()));
        when(providers.findAllByOrganisationId(tenant)).thenReturn(Flux.just(provider()));

        StepVerifier.create(useCase.byId(id, tenant)).assertNext(r -> assertEquals("https://i.example", r.issuer())).verifyComplete();
        StepVerifier.create(useCase.all(tenant)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.auditLog(id, tenant)).assertNext(log -> assertEquals("INITIATED", log.get(0).action())).verifyComplete();
        StepVerifier.create(useCase.byId(id, UUID.randomUUID())).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(useCase.auditLog(id, UUID.randomUUID())).expectError(IdentityProviderNotFoundException.class).verify();
    }

    @Test
    @DisplayName("link/initiate links a subject to a user under the organisation's issuer; no provider, a duplicate and a blank subject are refused")
    void initiateLink() {
        InitiateFederatedLinkUseCase useCase = new InitiateFederatedLinkUseCase(hashServicePort, providers, links);
        when(providers.findByOrganisationId(tenant)).thenReturn(Mono.just(provider()));
        when(hashServicePort.generateSovereignId("federated-link-creation")).thenReturn(Mono.just(id));
        when(links.create(any(FederatedLink.class))).thenAnswer(i -> Mono.just(i.getArgument(0))).thenReturn(Mono.error(new DuplicateFederatedLinkException("dup")));

        StepVerifier.create(useCase.execute(tenant, new LinkIdentityRequest(user, "sub-1"), EXECUTOR)).assertNext(r -> {
            assertEquals("https://i.example", r.issuer());
            assertEquals("sub-1", r.subject());
            assertEquals(user, r.userId());
            assertEquals("ACTIVE", r.status());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(tenant, new LinkIdentityRequest(user, "sub-1"), EXECUTOR)).expectError(DuplicateFederatedLinkException.class).verify();
        StepVerifier.create(useCase.execute(tenant, new LinkIdentityRequest(user, " "), EXECUTOR)).expectError(IllegalArgumentException.class).verify();
        when(providers.findByOrganisationId(tenant)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(tenant, new LinkIdentityRequest(user, "sub-2"), EXECUTOR)).expectError(IdentityProviderNotFoundException.class).verify();
    }

    @Test
    @DisplayName("revoke persists REVOKED with its entry; a second revoke and a foreign tenant are refused before any write")
    void revokeLink() {
        ControlFederatedLinkUseCase useCase = new ControlFederatedLinkUseCase(linkLookup, links);
        FederatedLink revoked = link();
        revoked.revoke(EXECUTOR);
        when(links.findById(id)).thenReturn(Mono.just(link())).thenReturn(Mono.just(revoked)).thenReturn(Mono.just(link()));
        when(links.updateStatus(eq(id), any(LinkStatus.class), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.revoke(id, tenant, EXECUTOR)).verifyComplete();
        verify(links).updateStatus(eq(id), eq(LinkStatus.REVOKED), any(AuditEntry.class));
        StepVerifier.create(useCase.revoke(id, tenant, EXECUTOR)).expectError(InvalidLinkStatusException.class).verify();
        StepVerifier.create(useCase.revoke(id, UUID.randomUUID(), EXECUTOR)).expectError(FederatedLinkNotFoundException.class).verify();
        verify(links, never()).updateStatus(eq(id), eq(LinkStatus.ACTIVE), any());
    }

    @Test
    @DisplayName("link retrieve maps by id and the tenant's list, optionally by status, scoped to the tenant")
    void retrieveLink() {
        RetrieveFederatedLinkUseCase useCase = new RetrieveFederatedLinkUseCase(linkLookup, links);
        when(links.findById(id)).thenReturn(Mono.just(link()));
        when(links.findAllByOrganisationId(tenant, null)).thenReturn(Flux.just(link()));
        when(links.findAllByOrganisationId(tenant, LinkStatus.REVOKED)).thenReturn(Flux.empty());

        StepVerifier.create(useCase.byId(id, tenant)).assertNext(r -> assertEquals("sub-1", r.subject())).verifyComplete();
        StepVerifier.create(useCase.byId(id, UUID.randomUUID())).expectError(FederatedLinkNotFoundException.class).verify();
        StepVerifier.create(useCase.all(tenant, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.all(tenant, LinkStatus.REVOKED)).verifyComplete();
    }
}
