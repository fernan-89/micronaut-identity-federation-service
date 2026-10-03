package com.thinklab.domain.exception;

/**
 * Domain Exception: the person authenticated at the provider, but their identity is not linked to a user of the organisation, and the organisation does not provision users automatically.
 *
 * <p>RFC 7807 mapping: HTTP 403 Forbidden.
 */
public class IdentityNotLinkedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00403";

    public IdentityNotLinkedException(String message) {
        super(ERROR_CODE, message);
    }
}
