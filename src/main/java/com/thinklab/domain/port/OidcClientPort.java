package com.thinklab.domain.port;

import com.thinklab.domain.model.IdentityClaims;
import com.thinklab.domain.model.IdentityProvider;
import reactor.core.publisher.Mono;

/**
 * Outbound Port to an OpenID Connect provider (authorization code flow with PKCE). The adapter speaks the protocol and validates the
 * ID token; the domain only sees the outcome.
 */
public interface OidcClientPort {

    /** The provider's authorization URL for this sign-in. Fails with {@code IdentityProviderUnavailableException}. */
    Mono<String> authorizationUrl(IdentityProvider provider, String redirectUri, String state, String nonce, String codeChallenge);

    /**
     * Exchanges the code and validates the ID token (signature against the provider's keys, issuer, audience, expiry and nonce).
     * Fails with {@code FederationAuthenticationException} when it cannot be trusted and {@code IdentityProviderUnavailableException}
     * when the provider cannot be reached.
     */
    Mono<IdentityClaims> authenticate(IdentityProvider provider, String clientSecret, String redirectUri, String code, String codeVerifier,
                                      String expectedNonce);
}
