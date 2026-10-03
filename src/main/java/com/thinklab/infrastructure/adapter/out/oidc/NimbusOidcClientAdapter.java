package com.thinklab.infrastructure.adapter.out.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.exception.IdentityProviderUnavailableException;
import com.thinklab.domain.model.IdentityClaims;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.application.usecase.IssuerPolicy;
import com.thinklab.domain.port.OidcClientPort;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * OpenID Connect client (authorization code flow with PKCE) on Nimbus (ADR-030, ADR-031).
 *
 * <p><b>What it trusts.</b> The provider's discovery document must name the configured issuer, and every endpoint in it must be https
 * (plain http only on a loopback host, for local development). The ID token is accepted only if its signature verifies against the
 * provider's published keys with an asymmetric algorithm from an allow-list (RS256, PS256, ES256 - never {@code none}, never an HMAC
 * keyed by something public), the issuer and audience match the configuration, it is unexpired, and its {@code nonce} is the one this
 * sign-in generated. Anything else is the same generic {@link FederationAuthenticationException}.
 *
 * <p><b>What it keeps.</b> Nothing: the access token the provider also returns is never read, and the claims it extracts live for the
 * length of one call.
 */
@Singleton
public class NimbusOidcClientAdapter implements OidcClientPort {

    private static final Logger log = LoggerFactory.getLogger(NimbusOidcClientAdapter.class);
    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256);
    private static final Argument<Map<String, Object>> JSON_OBJECT = Argument.mapOf(String.class, Object.class);

    private final HttpClient http;
    private final IssuerPolicy issuerPolicy;

    public NimbusOidcClientAdapter(@Client HttpClient http, IssuerPolicy issuerPolicy) {
        this.http = http;
        this.issuerPolicy = issuerPolicy;
    }

    @Override
    public Mono<String> authorizationUrl(IdentityProvider provider, String redirectUri, String state, String nonce, String codeChallenge) {
        return discover(provider.getIssuer()).map(document -> {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("response_type", "code");
            query.put("client_id", provider.getClientId());
            query.put("redirect_uri", redirectUri);
            query.put("scope", provider.getScopes());
            query.put("state", state);
            query.put("nonce", nonce);
            query.put("code_challenge", codeChallenge);
            query.put("code_challenge_method", "S256");
            String separator = document.authorizationEndpoint().contains("?") ? "&" : "?";
            return document.authorizationEndpoint() + separator + encode(query);
        });
    }

    @Override
    public Mono<IdentityClaims> authenticate(IdentityProvider provider, String clientSecret, String redirectUri, String code,
                                             String codeVerifier, String expectedNonce) {
        return discover(provider.getIssuer()).flatMap(document -> {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", redirectUri);
            form.put("client_id", provider.getClientId());
            form.put("client_secret", clientSecret);
            form.put("code_verifier", codeVerifier);
            HttpRequest<?> request = HttpRequest.POST(document.tokenEndpoint(), encode(form)).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
            return exchange(request)
                    .onErrorMap(HttpClientResponseException.class, e -> new FederationAuthenticationException("The identity provider refused the sign-in."))
                    .onErrorMap(HttpClientException.class, e -> new IdentityProviderUnavailableException("The identity provider could not be reached."))
                    .flatMap(tokens -> idToken(tokens)
                            .flatMap(idToken -> fetch(document.jwksUri()).flatMap(jwks -> validate(idToken, jwks, provider, expectedNonce))));
        });
    }

    private Mono<Map<String, Object>> exchange(HttpRequest<?> request) {
        return Mono.from(http.retrieve(request, JSON_OBJECT));
    }

    private Mono<Map<String, Object>> fetch(String url) {
        return exchange(HttpRequest.GET(url))
                .onErrorMap(HttpClientException.class, e -> new IdentityProviderUnavailableException("The identity provider could not be reached."));
    }

    private static Mono<String> idToken(Map<String, Object> tokens) {
        Object idToken = tokens.get("id_token");
        return idToken instanceof String text && !text.isBlank()
                ? Mono.just(text)
                : Mono.error(new FederationAuthenticationException("The identity provider returned no ID token."));
    }

    private Mono<IdentityClaims> validate(String idToken, Map<String, Object> jwks, IdentityProvider provider, String expectedNonce) {
        try {
            JWKSet keys = JWKSet.parse(jwks);
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, new ImmutableJWKSet<>(keys)));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<SecurityContext>(
                    new HashSet<>(Set.of(provider.getClientId())),
                    new JWTClaimsSet.Builder().issuer(provider.getIssuer()).build(),
                    new HashSet<>(Set.of("sub", "exp", "iat", "nonce")),
                    new HashSet<>()));
            JWTClaimsSet claims = processor.process(idToken, null);
            if (!expectedNonce.equals(claims.getStringClaim("nonce"))) {
                return Mono.error(new FederationAuthenticationException("The ID token does not belong to this sign-in."));
            }
            Object verified = claims.getClaim("email_verified");
            return Mono.just(new IdentityClaims(claims.getSubject(), claims.getStringClaim("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(verified), claims.getStringClaim("name")));
        } catch (ParseException | BadJOSEException | JOSEException | RuntimeException e) {
            log.warn("[OIDC] ID token rejected: {}", e.getClass().getSimpleName());
            return Mono.error(new FederationAuthenticationException("The identity provider's answer could not be trusted."));
        }
    }

    private Mono<Discovery> discover(String issuer) {
        return fetch(issuer + "/.well-known/openid-configuration").flatMap(document -> {
            String authorization = text(document, "authorization_endpoint");
            String token = text(document, "token_endpoint");
            String jwks = text(document, "jwks_uri");
            if (!issuer.equals(document.get("issuer")) || !issuerPolicy.trusted(authorization) || !issuerPolicy.trusted(token) || !issuerPolicy.trusted(jwks)) {
                return Mono.error(new IdentityProviderUnavailableException("The identity provider's discovery document is not usable."));
            }
            return Mono.just(new Discovery(authorization, token, jwks));
        });
    }

    private static String text(Map<String, Object> document, String key) {
        return document.get(key) instanceof String value ? value : null;
    }

    private static String encode(Map<String, String> parameters) {
        StringBuilder text = new StringBuilder();
        parameters.forEach((name, value) -> {
            if (!text.isEmpty()) {
                text.append('&');
            }
            text.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return text.toString();
    }

    private record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}
}
