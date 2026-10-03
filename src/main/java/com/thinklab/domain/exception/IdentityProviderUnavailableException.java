package com.thinklab.domain.exception;

/**
 * Domain Exception: the external identity provider could not be reached or answered something unusable.
 *
 * <p>RFC 7807 mapping: HTTP 502 Bad Gateway.
 */
public class IdentityProviderUnavailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00502";

    public IdentityProviderUnavailableException(String message) {
        super(ERROR_CODE, message);
    }
}
