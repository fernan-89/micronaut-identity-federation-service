package com.thinklab.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The short-lived state of one sign-in in flight (ADR-031): the random {@code state} that comes back on the callback, the
 * {@code nonce} the ID token must carry, the PKCE {@code codeVerifier} and the organisation. Single-use: it is consumed (read and
 * deleted in one step) when the callback arrives, and an unconsumed one expires on its own.
 */
public record LoginTransaction(String state, String nonce, String codeVerifier, UUID organisationId, Instant createdAt) {}
