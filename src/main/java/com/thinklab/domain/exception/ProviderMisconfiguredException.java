package com.thinklab.domain.exception;

/**
 * Domain Exception: the identity provider is registered but its client secret is not available to this service (the environment variable it names is not set).
 *
 * <p>RFC 7807 mapping: HTTP 503 Service Unavailable.
 */
public class ProviderMisconfiguredException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00503";

    public ProviderMisconfiguredException(String message) {
        super(ERROR_CODE, message);
    }
}
