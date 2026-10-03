package com.thinklab.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FederationExceptionsTest {

    @Test
    @DisplayName("each federation exception carries the error code its HTTP status is derived from")
    void codes() {
        assertEquals("ERR-FED-00409", new InvalidLinkStatusException("m").getErrorCode());
        assertEquals("ERR-FED-00409", new DuplicateFederatedLinkException("m").getErrorCode());
        assertEquals("ERR-FED-00409", new ProviderNotAvailableException("m").getErrorCode());
        assertEquals("ERR-FED-00404", new FederatedLinkNotFoundException("m").getErrorCode());
        assertEquals("ERR-FED-00400", new InvalidLoginStateException("m").getErrorCode());
        assertEquals("ERR-FED-00401", new FederationAuthenticationException("m").getErrorCode());
        assertEquals("ERR-FED-00403", new IdentityNotLinkedException("m").getErrorCode());
        assertEquals("ERR-FED-00502", new IdentityProviderUnavailableException("m").getErrorCode());
        assertEquals("ERR-FED-00503", new ProviderMisconfiguredException("m").getErrorCode());
        assertEquals("ERR-FED-00404", new IdentityProviderNotFoundException(UUID.randomUUID()).getErrorCode());
    }
}
