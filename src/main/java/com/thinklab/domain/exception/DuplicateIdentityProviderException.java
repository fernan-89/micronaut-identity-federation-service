package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an IdentityProvider is initiated for an organisation that already has one
 * (one provider per organisation).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateIdentityProviderException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00409";

    public DuplicateIdentityProviderException(String message) {
        super(ERROR_CODE, message);
    }
}
