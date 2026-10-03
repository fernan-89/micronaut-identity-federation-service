package com.thinklab.domain.exception;

/**
 * Domain Exception: a requested FederatedLink could not be resolved.
 *
 * <p>RFC 7807 mapping: HTTP 404 Not Found (one 404 code for the whole Service Domain).
 */
public class FederatedLinkNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00404";

    public FederatedLinkNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
