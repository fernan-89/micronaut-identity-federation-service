package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.FederatedLinkResponse;
import com.thinklab.application.dto.response.IdentityProviderResponse;
import com.thinklab.application.usecase.ControlFederatedLinkUseCase;
import com.thinklab.application.usecase.ControlIdentityProviderUseCase;
import com.thinklab.application.usecase.InitiateFederatedLinkUseCase;
import com.thinklab.application.usecase.InitiateIdentityProviderUseCase;
import com.thinklab.application.usecase.RetrieveFederatedLinkUseCase;
import com.thinklab.application.usecase.RetrieveIdentityProviderUseCase;
import com.thinklab.application.usecase.UpdateIdentityProviderUseCase;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the administration side of the {@code identity-federation} Service Domain: the organisation's OIDC provider
 * (the Control Record, at the root) and the links between external identities and users ({@code /link}, the secondary aggregate, like
 * {@code workflow-approval}'s {@code policy/}). Every route is tenant-scoped by {@code X-Tenant-Id}; there is no {@code DELETE}.
 * The public sign-in routes live in {@link LoginController}.
 */
@Controller("/identity-federation/v1")
public class IdentityProviderController {

    private static final Logger log = LoggerFactory.getLogger(IdentityProviderController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateIdentityProviderUseCase initiateProvider;
    private final UpdateIdentityProviderUseCase updateProvider;
    private final ControlIdentityProviderUseCase controlProvider;
    private final RetrieveIdentityProviderUseCase retrieveProvider;
    private final InitiateFederatedLinkUseCase initiateLink;
    private final ControlFederatedLinkUseCase controlLink;
    private final RetrieveFederatedLinkUseCase retrieveLink;

    public IdentityProviderController(InitiateIdentityProviderUseCase initiateProvider, UpdateIdentityProviderUseCase updateProvider,
                                      ControlIdentityProviderUseCase controlProvider, RetrieveIdentityProviderUseCase retrieveProvider,
                                      InitiateFederatedLinkUseCase initiateLink, ControlFederatedLinkUseCase controlLink,
                                      RetrieveFederatedLinkUseCase retrieveLink) {
        this.initiateProvider = initiateProvider;
        this.updateProvider = updateProvider;
        this.controlProvider = controlProvider;
        this.retrieveProvider = retrieveProvider;
        this.initiateLink = initiateLink;
        this.controlLink = controlLink;
        this.retrieveLink = retrieveLink;
    }

    /** Behavior Qualifier: {@code initiate}. Registers the organisation's OIDC provider (DISABLED until enabled). */
    @Post("/initiate")
    public Mono<HttpResponse<IdentityProviderResponse>> initiate(@Header(TENANT_HEADER) @NotBlank String tenantId,
                                                                 @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                                                 @Body @Valid IdentityProviderSettingsRequest request) {
        log.info("[ACTION: INITIATE_IDENTITY_PROVIDER] [EXECUTOR: {}] Registering a provider for organisation: {}", executor, tenantId);

        return initiateProvider.execute(UUID.fromString(tenantId), request, executor).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches the provider by UUID, scoped to the tenant. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<IdentityProviderResponse>> retrieveById(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_IDENTITY_PROVIDER] Received request to get provider by ID: {}", id);

        return retrieveProvider.byId(id, UUID.fromString(tenantId)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). The tenant's provider (at most one). */
    @Get("/retrieve")
    public Mono<List<IdentityProviderResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_IDENTITY_PROVIDERS] Received request for organisation: {}", tenantId);

        return Mono.defer(() -> retrieveProvider.all(UUID.fromString(tenantId)).collectList());
    }

    /** Behavior Qualifier: {@code update}. Replaces the settings; only legal while DISABLED. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                           @Body @Valid IdentityProviderSettingsRequest request) {
        log.info("[ACTION: UPDATE_IDENTITY_PROVIDER] [EXECUTOR: {}] Received request to update provider ID: {}", executor, id);

        return updateProvider.execute(id, UUID.fromString(tenantId), request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/enable}. DISABLED -> ACTIVE. */
    @Put("/{id}/control/enable")
    public Mono<HttpResponse<Void>> controlEnable(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                  @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlIdentityProviderUseCase.Action.ENABLE, executor);
    }

    /** Behavior Qualifier: {@code control/disable}. ACTIVE -> DISABLED. */
    @Put("/{id}/control/disable")
    public Mono<HttpResponse<Void>> controlDisable(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                   @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlIdentityProviderUseCase.Action.DISABLE, executor);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the provider. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_IDENTITY_PROVIDER_AUDIT_LOG] Received request for audit ledger of provider ID: {}", id);

        return retrieveProvider.auditLog(id, UUID.fromString(tenantId));
    }

    /** Behavior Qualifier: {@code link/initiate}. Links an external identity (the provider's subject) to a user. */
    @Post("/link/initiate")
    public Mono<HttpResponse<FederatedLinkResponse>> linkInitiate(@Header(TENANT_HEADER) @NotBlank String tenantId,
                                                                  @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                                                  @Body @Valid LinkIdentityRequest request) {
        log.info("[ACTION: INITIATE_FEDERATED_LINK] [EXECUTOR: {}] Linking an identity to user {} for organisation: {}", executor, request.userId(), tenantId);

        return initiateLink.execute(UUID.fromString(tenantId), request, executor).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code link/retrieve}. Fetches one link, scoped to the tenant. */
    @Get("/link/{id}/retrieve")
    public Mono<HttpResponse<FederatedLinkResponse>> linkRetrieveById(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_FEDERATED_LINK] Received request to get link by ID: {}", id);

        return retrieveLink.byId(id, UUID.fromString(tenantId)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code link/retrieve} (collection). The tenant's links, optionally by status. */
    @Get("/link/retrieve")
    public Mono<List<FederatedLinkResponse>> linkRetrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @QueryValue @Nullable LinkStatus status) {
        log.info("[ACTION: RETRIEVE_FEDERATED_LINKS] Received request for organisation: {} status: {}", tenantId, status);

        return Mono.defer(() -> retrieveLink.all(UUID.fromString(tenantId), status).collectList());
    }

    /** Behavior Qualifier: {@code link/control/revoke}. ACTIVE -> REVOKED (terminal): the person can no longer sign in through the provider. */
    @Put("/link/{id}/control/revoke")
    public Mono<HttpResponse<Void>> linkControlRevoke(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                      @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        log.info("[ACTION: REVOKE_FEDERATED_LINK] [EXECUTOR: {}] Revoking link ID: {}", executor, id);

        return controlLink.revoke(id, UUID.fromString(tenantId), executor).thenReturn(HttpResponse.noContent());
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlIdentityProviderUseCase.Action action, String executor) {
        log.info("[ACTION: CONTROL_IDENTITY_PROVIDER] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return controlProvider.execute(id, UUID.fromString(tenantId), action, executor).thenReturn(HttpResponse.noContent());
    }
}
