package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.response.SessionResponse;
import com.thinklab.application.usecase.CompleteLoginUseCase;
import com.thinklab.application.usecase.InitiateLoginUseCase;
import com.thinklab.domain.exception.FederationAuthenticationException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * The public sign-in routes of the {@code identity-federation} Service Domain (both are public paths: the person has no token yet).
 *
 * <ul>
 *   <li>{@code GET login/initiate?organisationId=} answers {@code 302} to the provider's authorization URL.</li>
 *   <li>{@code GET login/callback?code=&state=} is where the provider sends the browser back. It answers the session
 *       ({@code 200}, the same shape as a password login); the platform gateway turns that into an HttpOnly cookie and a redirect, so
 *       the refresh token never reaches the page's scripts. A provider-side refusal ({@code error=...}) is a {@code 401}, with
 *       nothing the provider said echoed back.</li>
 * </ul>
 */
@Controller("/identity-federation/v1/login")
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final InitiateLoginUseCase initiateLoginUseCase;
    private final CompleteLoginUseCase completeLoginUseCase;

    public LoginController(InitiateLoginUseCase initiateLoginUseCase, CompleteLoginUseCase completeLoginUseCase) {
        this.initiateLoginUseCase = initiateLoginUseCase;
        this.completeLoginUseCase = completeLoginUseCase;
    }

    /** Behavior Qualifier: {@code login/initiate}. Sends the browser to the organisation's identity provider. */
    @Get("/initiate")
    public Mono<MutableHttpResponse<Void>> initiate(@QueryValue @NotBlank String organisationId) {
        log.info("[ACTION: INITIATE_LOGIN] Federated sign-in requested for organisation: {}", organisationId);

        return Mono.defer(() -> initiateLoginUseCase.execute(UUID.fromString(organisationId)))
                .map(url -> HttpResponse.<Void>status(io.micronaut.http.HttpStatus.FOUND).headers(headers -> headers.location(URI.create(url))));
    }

    /** Behavior Qualifier: {@code login/callback}. Completes the sign-in and answers the session. */
    @Get("/callback")
    public Mono<HttpResponse<SessionResponse>> callback(@QueryValue @Nullable String code, @QueryValue @Nullable String state,
                                                        @QueryValue @Nullable String error) {
        log.info("[ACTION: LOGIN_CALLBACK] Callback received");

        if (error != null || code == null || state == null) {
            return Mono.error(new FederationAuthenticationException("The identity provider did not authenticate the person."));
        }
        return completeLoginUseCase.execute(code, state).map(HttpResponse::ok);
    }
}
