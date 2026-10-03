package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.dto.response.SessionResponse;
import com.thinklab.application.usecase.CompleteLoginUseCase;
import com.thinklab.application.usecase.ControlFederatedLinkUseCase;
import com.thinklab.application.usecase.ControlIdentityProviderUseCase;
import com.thinklab.application.usecase.InitiateFederatedLinkUseCase;
import com.thinklab.application.usecase.InitiateIdentityProviderUseCase;
import com.thinklab.application.usecase.InitiateLoginUseCase;
import com.thinklab.application.usecase.RetrieveFederatedLinkUseCase;
import com.thinklab.application.usecase.RetrieveIdentityProviderUseCase;
import com.thinklab.application.usecase.UpdateIdentityProviderUseCase;
import com.thinklab.domain.exception.DuplicateIdentityProviderException;
import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.exception.InvalidLoginStateException;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FederationControllersTest {

    private static final String EXECUTOR = "admin";

    private final InitiateIdentityProviderUseCase initiateProvider = mock(InitiateIdentityProviderUseCase.class);
    private final UpdateIdentityProviderUseCase updateProvider = mock(UpdateIdentityProviderUseCase.class);
    private final ControlIdentityProviderUseCase controlProvider = mock(ControlIdentityProviderUseCase.class);
    private final RetrieveIdentityProviderUseCase retrieveProvider = mock(RetrieveIdentityProviderUseCase.class);
    private final InitiateFederatedLinkUseCase initiateLink = mock(InitiateFederatedLinkUseCase.class);
    private final ControlFederatedLinkUseCase controlLink = mock(ControlFederatedLinkUseCase.class);
    private final RetrieveFederatedLinkUseCase retrieveLink = mock(RetrieveFederatedLinkUseCase.class);
    private final InitiateLoginUseCase initiateLogin = mock(InitiateLoginUseCase.class);
    private final CompleteLoginUseCase completeLogin = mock(CompleteLoginUseCase.class);

    private final IdentityProviderController admin = new IdentityProviderController(initiateProvider, updateProvider, controlProvider,
            retrieveProvider, initiateLink, controlLink, retrieveLink);
    private final LoginController login = new LoginController(initiateLogin, completeLogin);

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final IdentityProviderResponse providerResponse = new IdentityProviderResponse(id, org, "https://i.example", "c", "OIDC_SECRET",
            "openid", false, "DISABLED", Instant.now(), Instant.now());
    private final FederatedLinkResponse linkResponse = new FederatedLinkResponse(id, org, "https://i.example", "sub-1", UUID.randomUUID(),
            "ACTIVE", Instant.now(), Instant.now());
    private final IdentityProviderSettingsRequest settings = new IdentityProviderSettingsRequest("https://i.example", "c", "OIDC_SECRET", null, null);

    @Test
    @DisplayName("provider initiate answers 201 and propagates a duplicate")
    void initiate() {
        when(initiateProvider.execute(org, settings, EXECUTOR)).thenReturn(Mono.just(providerResponse)).thenReturn(Mono.error(new DuplicateIdentityProviderException("dup")));

        StepVerifier.create(admin.initiate(org.toString(), EXECUTOR, settings)).assertNext(r -> {
            assertEquals(HttpStatus.CREATED, r.getStatus());
            assertEquals(id, r.body().id());
        }).verifyComplete();
        StepVerifier.create(admin.initiate(org.toString(), EXECUTOR, settings)).expectError(DuplicateIdentityProviderException.class).verify();
    }

    @Test
    @DisplayName("provider retrieve by id is tenant-scoped, the collection lists the tenant's provider, and the audit log is returned")
    void retrieve() {
        when(retrieveProvider.byId(id, org)).thenReturn(Mono.just(providerResponse)).thenReturn(Mono.error(new IdentityProviderNotFoundException(id)));
        when(retrieveProvider.all(org)).thenReturn(Flux.just(providerResponse));
        when(retrieveProvider.auditLog(id, org)).thenReturn(Mono.just(List.of(new AuditEntryResponse(Instant.now(), "INITIATED", EXECUTOR, null, "DISABLED", "d"))));

        StepVerifier.create(admin.retrieveById(id, org.toString())).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(admin.retrieveById(id, org.toString())).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(admin.retrieveAll(org.toString())).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(admin.retrieveAuditLog(id, org.toString())).assertNext(list -> assertEquals("INITIATED", list.get(0).action())).verifyComplete();
    }

    @Test
    @DisplayName("provider update, enable and disable answer 204 through the matching action")
    void updateAndControl() {
        when(updateProvider.execute(id, org, settings, EXECUTOR)).thenReturn(Mono.empty());
        when(controlProvider.execute(id, org, ControlIdentityProviderUseCase.Action.ENABLE, EXECUTOR)).thenReturn(Mono.empty());
        when(controlProvider.execute(id, org, ControlIdentityProviderUseCase.Action.DISABLE, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(admin.update(id, org.toString(), EXECUTOR, settings)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(admin.controlEnable(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(admin.controlDisable(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("link initiate answers 201; link retrieve (one, and the tenant's list by status) and revoke answer 200 and 204")
    void links() {
        LinkIdentityRequest request = new LinkIdentityRequest(UUID.randomUUID(), "sub-1");
        when(initiateLink.execute(org, request, EXECUTOR)).thenReturn(Mono.just(linkResponse));
        when(retrieveLink.byId(id, org)).thenReturn(Mono.just(linkResponse));
        when(retrieveLink.all(org, null)).thenReturn(Flux.just(linkResponse));
        when(retrieveLink.all(org, LinkStatus.REVOKED)).thenReturn(Flux.empty());
        when(controlLink.revoke(id, org, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(admin.linkInitiate(org.toString(), EXECUTOR, request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(admin.linkRetrieveById(id, org.toString())).assertNext(r -> assertEquals("sub-1", r.body().subject())).verifyComplete();
        StepVerifier.create(admin.linkRetrieveAll(org.toString(), null)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(admin.linkRetrieveAll(org.toString(), LinkStatus.REVOKED)).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
        StepVerifier.create(admin.linkControlRevoke(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("login/initiate answers 302 to the provider's URL; an unknown organisation propagates and a malformed id is a validation error")
    void loginInitiate() {
        when(initiateLogin.execute(org)).thenReturn(Mono.just("https://i.example/authorize?state=x")).thenReturn(Mono.error(new IdentityProviderNotFoundException("none")));

        StepVerifier.create(login.initiate(org.toString())).assertNext(r -> {
            assertEquals(HttpStatus.FOUND, r.getStatus());
            assertEquals("https://i.example/authorize?state=x", r.getHeaders().get("Location"));
        }).verifyComplete();
        StepVerifier.create(login.initiate(org.toString())).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(login.initiate("not-a-uuid")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("login/callback answers the session; a provider-side error or a missing code or state is a 401 without calling the use case; a bad state propagates")
    void loginCallback() {
        when(completeLogin.execute("code-1", "state-1")).thenReturn(Mono.just(new SessionResponse("a", "Bearer", 600, "r", 3600)));
        when(completeLogin.execute("code-1", "bad")).thenReturn(Mono.error(new InvalidLoginStateException("bad")));

        StepVerifier.create(login.callback("code-1", "state-1", null)).assertNext(r -> {
            assertEquals(HttpStatus.OK, r.getStatus());
            assertEquals("a", r.body().accessToken());
        }).verifyComplete();
        StepVerifier.create(login.callback("code-1", "bad", null)).expectError(InvalidLoginStateException.class).verify();
        StepVerifier.create(login.callback("code-1", "state-1", "access_denied")).expectError(FederationAuthenticationException.class).verify();
        StepVerifier.create(login.callback(null, "state-1", null)).expectError(FederationAuthenticationException.class).verify();
        StepVerifier.create(login.callback("code-1", null, null)).expectError(FederationAuthenticationException.class).verify();
    }
}
