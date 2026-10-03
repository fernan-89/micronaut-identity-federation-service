package com.thinklab.domain.exception;

/**
 * Domain Exception: the callback carried a state that is unknown, already used or expired - a replayed or forged callback.
 *
 * <p>RFC 7807 mapping: HTTP 400 Bad Request.
 */
public class InvalidLoginStateException extends BusinessException {

    private static final String ERROR_CODE = "ERR-FED-00400";

    public InvalidLoginStateException(String message) {
        super(ERROR_CODE, message);
    }
}
