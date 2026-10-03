package com.thinklab.domain.exception;

/**
 * Domain Exception: the identity provider refused, or its answer could not be trusted (bad signature, wrong issuer or audience, wrong nonce, expired); also a user the platform will not open a session for. The message never says which part failed.
 *
 * <p>RFC 7807 mapping: HTTP 401 Unauthorized.
 */
public class FederationAuthenticationException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00401";

    public FederationAuthenticationException(String message) {
        super(ERROR_CODE, message);
    }
}
