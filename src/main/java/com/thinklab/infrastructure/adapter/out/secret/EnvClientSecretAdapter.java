package com.thinklab.infrastructure.adapter.out.secret;

import com.thinklab.domain.port.ClientSecretPort;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves a client secret reference from the process environment (ADR-031). The value is read at the moment it is needed and is
 * never stored, logged or returned by the API; a blank value counts as not configured. A secret-manager adapter would implement
 * the same port.
 */
@Singleton
public class EnvClientSecretAdapter implements ClientSecretPort {

    private final Function<String, String> environment;

    @Inject
    public EnvClientSecretAdapter() {
        this(System::getenv);
    }

    /** Test seam: where the variables come from. */
    EnvClientSecretAdapter(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String reference) {
        return Optional.ofNullable(environment.apply(reference)).filter(value -> !value.isBlank());
    }
}
