package com.thinklab.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code thinklab.federation.*}.
 *
 * <p>{@code redirectUri} is the callback URL as the BROWSER sees it (through the platform gateway and the web app's {@code /api}
 * prefix); it is what the identity provider is told, and what an administrator registers at the provider. {@code transactionTtlSeconds}
 * is how long a started sign-in may take before its state is refused. {@code insecureHosts} are extra hosts (beyond loopback) whose
 * identity provider may be reached over plain http - for a local or containerised test provider only, never production.
 */
@ConfigurationProperties("thinklab.federation")
public class FederationProperties {

    private String redirectUri = "http://localhost:5173/api/identity-federation/v1/login/callback";
    private long transactionTtlSeconds = 600;
    private List<String> insecureHosts = List.of();

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public long getTransactionTtlSeconds() {
        return transactionTtlSeconds;
    }

    public void setTransactionTtlSeconds(long transactionTtlSeconds) {
        this.transactionTtlSeconds = transactionTtlSeconds;
    }

    public List<String> getInsecureHosts() {
        return insecureHosts;
    }

    public void setInsecureHosts(List<String> insecureHosts) {
        this.insecureHosts = insecureHosts;
    }
}
