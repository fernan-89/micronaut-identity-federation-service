package com.thinklab.domain.exception;

/**
 * Domain Exception: the external identity, or the user, is already linked within the organisation.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class DuplicateFederatedLinkException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00409";

    public DuplicateFederatedLinkException(String message) {
        super(ERROR_CODE, message);
    }
}
