package com.thinklab.domain.port;

import java.util.Optional;

/** Outbound Port that resolves the NAME of a client secret to its value, at the moment it is needed. Nothing is stored (ADR-031). */
public interface ClientSecretPort {

    Optional<String> resolve(String reference);
}
