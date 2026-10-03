package com.thinklab.domain.exception;

/**
 * Domain Exception: the organisation's identity provider exists but is not ACTIVE, so nobody can sign in through it.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class ProviderNotAvailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00409";

    public ProviderNotAvailableException(String message) {
        super(ERROR_CODE, message);
    }
}
