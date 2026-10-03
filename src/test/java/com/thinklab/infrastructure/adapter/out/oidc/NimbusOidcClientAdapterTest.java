package com.thinklab.infrastructure.adapter.out.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thinklab.application.config.FederationProperties;
import com.thinklab.application.usecase.IssuerPolicy;
import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.exception.IdentityProviderUnavailableException;
import com.thinklab.domain.model.IdentityClaims;
import com.thinklab.domain.model.IdentityProvider;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class NimbusOidcClientAdapterTest {

    private static final String ISSUER = "http://localhost:9000";
    private static final String CLIENT = "thinklab-client";
    private static final String REDIRECT = "http://localhost:5173/api/identity-federation/v1/login/callback";

    private final HttpClient http = mock(HttpClient.class);
    private final NimbusOidcClientAdapter adapter = new NimbusOidcClientAdapter(http, new IssuerPolicy(new FederationProperties()));
    private final IdentityProvider provider = IdentityProvider.createNew(UUID.randomUUID(), UUID.randomUUID(), ISSUER, CLIENT, "OIDC_SECRET", null, false, "admin");
    private ECKey key;
    private Map<String, Object> discovery;
    private Map<String, Object> jwks;
    private Function<HttpRequest<?>, Object> tokenEndpoint;

    @BeforeEach
    void setUp() throws JOSEException {
        key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        jwks = new JWKSet(key.toPublicJWK()).toJSONObject();
        discovery = new LinkedHashMap<>();
        discovery.put("issuer", ISSUER);
        discovery.put("authorization_endpoint", ISSUER + "/authorize");
        discovery.put("token_endpoint", ISSUER + "/token");
        discovery.put("jwks_uri", ISSUER + "/jwks");
        tokenEndpoint = request -> Map.of("id_token", token(claims().build()), "access_token", "never-read");
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenAnswer(invocation -> {
            HttpRequest<?> request = invocation.getArgument(0);
            String url = request.getUri().toString();
            Object answer;
            if (url.endsWith("/.well-known/openid-configuration")) {
                answer = discovery;
            } else if (url.endsWith("/jwks")) {
                answer = jwks;
            } else {
                assertEquals(HttpMethod.POST, request.getMethod());
                answer = tokenEndpoint.apply(request);
            }
            return answer instanceof Throwable failure ? Mono.error(failure) : Mono.just(answer);
        });
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT).subject("sub-1").claim("nonce", "nonce-1")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email", "ada@example.com").claim("email_verified", true).claim("name", "Ada");
    }

    private String token(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private StepVerifier.FirstStep<IdentityClaims> authenticate() {
        return StepVerifier.create(adapter.authenticate(provider, "the-secret", REDIRECT, "code-1", "verifier-1", "nonce-1"));
    }

    private static HttpClientResponseException refusal() {
        return new HttpClientResponseException("invalid_grant", HttpResponse.status(HttpStatus.BAD_REQUEST));
    }

    // ----------------------------------------------------------------------------------- authorization URL

    @Test
    @DisplayName("the authorization URL carries the code flow parameters, PKCE S256 and the scopes, URL-encoded, on the discovered endpoint")
    void authorizationUrl() {
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "state-1", "nonce-1", "challenge-1")).assertNext(url -> {
            assertTrue(url.startsWith(ISSUER + "/authorize?response_type=code&client_id=thinklab-client&redirect_uri="));
            assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fapi%2Fidentity-federation%2Fv1%2Flogin%2Fcallback"));
            assertTrue(url.contains("&scope=openid+email+profile&state=state-1&nonce=nonce-1&code_challenge=challenge-1&code_challenge_method=S256"));
        }).verifyComplete();
    }

    @Test
    @DisplayName("an authorization endpoint that already has a query gets the parameters appended with &")
    void authorizationUrlWithQuery() {
        discovery.put("authorization_endpoint", ISSUER + "/authorize?tenant=x");

        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).assertNext(url -> assertTrue(url.startsWith(ISSUER + "/authorize?tenant=x&response_type=code"))).verifyComplete();
    }

    @Test
    @DisplayName("a discovery document that names another issuer, an insecure or missing or malformed endpoint, or cannot be fetched is the provider being unavailable")
    void discoveryProblems() {
        discovery.put("issuer", "https://evil.example");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("issuer", ISSUER);
        discovery.put("authorization_endpoint", "http://evil.example/authorize");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("authorization_endpoint", ISSUER + "/authorize");
        discovery.put("issuer", ISSUER);
        discovery.put("token_endpoint", "http://evil.example/token");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("token_endpoint", ISSUER + "/token");
        discovery.remove("jwks_uri");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("jwks_uri", 42);
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("jwks_uri", "http:///jwks");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        discovery.put("jwks_uri", "not a url");
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();

        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(Mono.error(new HttpClientException("connection refused")));
        StepVerifier.create(adapter.authorizationUrl(provider, REDIRECT, "s", "n", "c")).expectError(IdentityProviderUnavailableException.class).verify();
    }

    // ----------------------------------------------------------------------------------- authenticate

    @Test
    @DisplayName("a valid ID token yields the subject, email, verified flag and name; the token request is a form post with the secret and the PKCE verifier")
    void authenticates() {
        java.util.List<HttpRequest<?>> sent = new java.util.ArrayList<>();
        Function<HttpRequest<?>, Object> original = tokenEndpoint;
        tokenEndpoint = request -> {
            sent.add(request);
            return original.apply(request);
        };

        authenticate().expectNext(new IdentityClaims("sub-1", "ada@example.com", true, "Ada")).verifyComplete();

        HttpRequest<?> post = sent.get(0);
        assertEquals(MediaType.APPLICATION_FORM_URLENCODED_TYPE, post.getContentType().orElseThrow());
        String body = (String) post.getBody().orElseThrow();
        assertTrue(body.contains("grant_type=authorization_code") && body.contains("code=code-1") && body.contains("client_secret=the-secret")
                && body.contains("code_verifier=verifier-1") && body.contains("client_id=thinklab-client"));
        assertEquals(ISSUER + "/token", post.getUri().toString());
    }

    @Test
    @DisplayName("email_verified as the string true counts; absent or false does not; missing email and name are tolerated")
    void claimShapes() {
        tokenEndpoint = request -> Map.of("id_token", token(claims().claim("email_verified", "true").build()));
        authenticate().expectNextMatches(IdentityClaims::emailVerified).verifyComplete();

        tokenEndpoint = request -> Map.of("id_token", token(claims().claim("email_verified", false).build()));
        authenticate().expectNextMatches(claims -> !claims.emailVerified()).verifyComplete();

        tokenEndpoint = request -> Map.of("id_token", token(new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT).subject("sub-2").claim("nonce", "nonce-1")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(60))).build()));
        authenticate().expectNext(new IdentityClaims("sub-2", null, false, null)).verifyComplete();
    }

    @Test
    @DisplayName("a provider refusal of the code is the generic authentication failure; an unreachable token endpoint is the provider being unavailable")
    void tokenEndpointFailures() {
        tokenEndpoint = request -> refusal();
        authenticate().expectError(FederationAuthenticationException.class).verify();

        tokenEndpoint = request -> new HttpClientException("connection reset");
        authenticate().expectError(IdentityProviderUnavailableException.class).verify();
    }

    @Test
    @DisplayName("a token response without an ID token (or with a blank one, or a non-text one) is the generic authentication failure")
    void noIdToken() {
        tokenEndpoint = request -> Map.of("access_token", "x");
        authenticate().expectError(FederationAuthenticationException.class).verify();
        tokenEndpoint = request -> Map.of("id_token", " ");
        authenticate().expectError(FederationAuthenticationException.class).verify();
        tokenEndpoint = request -> Map.of("id_token", 42);
        authenticate().expectError(FederationAuthenticationException.class).verify();
    }

    @Test
    @DisplayName("an ID token with the wrong issuer, audience, nonce, a missing claim, an expiry in the past, or garbage is the generic authentication failure")
    void untrustedClaims() {
        for (JWTClaimsSet bad : new JWTClaimsSet[]{
                claims().issuer("https://evil.example").build(),
                claims().audience("someone-else").build(),
                claims().claim("nonce", "another-sign-in").build(),
                claims().claim("nonce", null).build(),
                claims().subject(null).build(),
                claims().expirationTime(Date.from(Instant.now().minusSeconds(3600))).build()}) {
            tokenEndpoint = request -> Map.of("id_token", token(bad));
            authenticate().expectError(FederationAuthenticationException.class).verify();
        }
        tokenEndpoint = request -> Map.of("id_token", "this-is-not-a-jwt");
        authenticate().expectError(FederationAuthenticationException.class).verify();
    }

    @Test
    @DisplayName("an ID token signed with another key, or with an HMAC (never accepted), or against an unparsable key set, is the generic authentication failure")
    void untrustedSignatures() throws JOSEException {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").build(), claims().build());
        forged.sign(new ECDSASigner(attacker));
        tokenEndpoint = request -> Map.of("id_token", forged.serialize());
        authenticate().expectError(FederationAuthenticationException.class).verify();

        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims().build());
        hmac.sign(new MACSigner("a-shared-secret-that-is-at-least-32-bytes-long!"));
        tokenEndpoint = request -> Map.of("id_token", hmac.serialize());
        authenticate().expectError(FederationAuthenticationException.class).verify();

        tokenEndpoint = request -> Map.of("id_token", token(claims().build()));
        jwks = Map.of("keys", "not-an-array");
        authenticate().expectError(FederationAuthenticationException.class).verify();
    }

    @Test
    @DisplayName("a key set that cannot be fetched is the provider being unavailable")
    void jwksUnavailable() {
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenAnswer(invocation -> {
            HttpRequest<?> request = invocation.getArgument(0);
            String url = request.getUri().toString();
            if (url.endsWith("/jwks")) {
                return Mono.error(new HttpClientException("timeout"));
            }
            return Mono.just(url.endsWith("/.well-known/openid-configuration") ? discovery : Map.of("id_token", token(claims().build())));
        });

        authenticate().expectError(IdentityProviderUnavailableException.class).verify();
    }
}
