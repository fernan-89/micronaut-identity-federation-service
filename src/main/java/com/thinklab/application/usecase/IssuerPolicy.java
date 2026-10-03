package com.thinklab.application.usecase;

import com.thinklab.application.config.FederationProperties;
import jakarta.inject.Singleton;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Which identity provider URLs may be trusted (ADR-031): https always; plain http only for a loopback host or a host the operator
 * listed in {@code thinklab.federation.insecure-hosts} (a local or containerised test provider - never production). The same rule
 * guards the issuer an administrator registers and every endpoint the provider's discovery document names.
 */
@Singleton
public class IssuerPolicy {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final List<String> insecureHosts;

    public IssuerPolicy(FederationProperties properties) {
        this.insecureHosts = properties.getInsecureHosts();
    }

    public boolean trusted(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && host != null && (LOOPBACK.contains(host) || insecureHosts.contains(host)));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Fails with {@link IllegalArgumentException} (a 400) when the URL may not be trusted. */
    public void requireTrusted(String url) {
        if (!trusted(url)) {
            throw new IllegalArgumentException("The issuer must be an https URL (http is accepted only for localhost or a host the operator allowed).");
        }
    }
}
