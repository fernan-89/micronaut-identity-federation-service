package com.thinklab.domain.exception;

/**
 * Domain Exception: an illegal lifecycle move on a FederatedLink (only an ACTIVE link can be revoked).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class InvalidLinkStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00409";

    public InvalidLinkStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
