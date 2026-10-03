package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidIdentityProviderStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core Domain Model representing the IdentityProvider Aggregate Root (Control Record) of the {@code identity-federation} Service
 * Domain: the OIDC provider one organisation signs in through (ADR-030). One per organisation.
 *
 * <p><b>It never holds a secret.</b> The client secret is named by {@code clientSecretRef} - the NAME of an environment variable (or
 * secret-manager entry) the operator provides - and the value is resolved at the moment it is needed, never stored (ADR-031).
 *
 * <p>Lifecycle: {@code DISABLED <-> ACTIVE}; it starts {@code DISABLED}. Settings are editable only while {@code DISABLED}: changing
 * the issuer or client of a provider people are signing in through would silently change who they are.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class IdentityProvider {

    public static final String DEFAULT_SCOPES = "openid email profile";
    static final Pattern SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    static final Pattern SCOPES = Pattern.compile("[A-Za-z0-9:._-]+( [A-Za-z0-9:._-]+)*");

    private final UUID id;
    private final UUID organisationId;
    private String issuer;
    private String clientId;
    private String clientSecretRef;
    private String scopes;
    private boolean autoProvision;
    private ProviderStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AuditEntry> auditTrail;

    private IdentityProvider(UUID id, UUID organisationId, String issuer, String clientId, String clientSecretRef, String scopes,
                             boolean autoProvision, ProviderStatus status, Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecretRef = clientSecretRef;
        this.scopes = scopes;
        this.autoProvision = autoProvision;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.auditTrail = auditTrail;
    }

    /** Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). Starts {@code DISABLED}. */
    public static IdentityProvider createNew(UUID id, UUID organisationId, String issuer, String clientId, String clientSecretRef,
                                             String scopes, boolean autoProvision, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for IdentityProvider creation.");
        }
        requireExecutor(executor);
        Instant now = Instant.now();
        IdentityProvider provider = new IdentityProvider(id, organisationId, validIssuer(issuer), validClientId(clientId),
                validSecretRef(clientSecretRef), validScopes(scopes), autoProvision, ProviderStatus.DISABLED, now, now, new ArrayList<>());
        provider.auditTrail.add(new AuditEntry(now, "INITIATED", executor, null, ProviderStatus.DISABLED.name(), "Identity provider registered."));
        return provider;
    }

    /** Reconstitutes an existing IdentityProvider aggregate from the persistence layer. */
    public static IdentityProvider reconstitute(UUID id, UUID organisationId, String issuer, String clientId, String clientSecretRef,
                                                String scopes, boolean autoProvision, ProviderStatus status, Instant createdAt,
                                                Instant updatedAt, List<AuditEntry> auditTrail) {
        if (id == null || organisationId == null || issuer == null || clientId == null || clientSecretRef == null || scopes == null
                || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Every field except the audit trail is mandatory to reconstitute an IdentityProvider.");
        }
        return new IdentityProvider(id, organisationId, issuer, clientId, clientSecretRef, scopes, autoProvision, status, createdAt, updatedAt,
                auditTrail == null ? new ArrayList<>() : new ArrayList<>(auditTrail));
    }

    /** Behavior Qualifier: {@code update}. Replaces the settings; only legal while {@code DISABLED}. */
    public AuditEntry updateSettings(String newIssuer, String newClientId, String newClientSecretRef, String newScopes,
                                     boolean newAutoProvision, String executor) {
        requireExecutor(executor);
        if (status != ProviderStatus.DISABLED) {
            throw new InvalidIdentityProviderStatusException("Compliance Violation: an ACTIVE identity provider cannot be edited; disable it first.");
        }
        this.issuer = validIssuer(newIssuer);
        this.clientId = validClientId(newClientId);
        this.clientSecretRef = validSecretRef(newClientSecretRef);
        this.scopes = validScopes(newScopes);
        this.autoProvision = newAutoProvision;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "UPDATED", executor, status.name(), status.name(), "Settings updated.");
        auditTrail.add(entry);
        return entry;
    }

    /** Behavior Qualifier: {@code control/enable}. {@code DISABLED -> ACTIVE}. */
    public AuditEntry enable(String executor) {
        return transition(ProviderStatus.DISABLED, ProviderStatus.ACTIVE, executor, "Enabled.");
    }

    /** Behavior Qualifier: {@code control/disable}. {@code ACTIVE -> DISABLED}. */
    public AuditEntry disable(String executor) {
        return transition(ProviderStatus.ACTIVE, ProviderStatus.DISABLED, executor, "Disabled.");
    }

    private AuditEntry transition(ProviderStatus required, ProviderStatus target, String executor, String detail) {
        requireExecutor(executor);
        if (status != required) {
            throw new InvalidIdentityProviderStatusException(String.format(
                    "Compliance Violation: cannot move an IdentityProvider from [%s] to [%s]; only %s can.", status, target, required));
        }
        this.status = target;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "STATUS_CHANGED", executor, required.name(), target.name(), detail);
        auditTrail.add(entry);
        return entry;
    }

    /** The issuer must be an http(s) URL with a host and no query, fragment or credentials. (That plain http is only for a loopback host or an operator-allowed host is the application's IssuerPolicy.) */
    private static String validIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer is mandatory.");
        }
        URI uri;
        try {
            uri = new URI(issuer.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Issuer is not a valid URL.");
        }
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Issuer must be an http(s) URL with a host; whether plain http is acceptable is the operator's policy.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Issuer must not carry a query, a fragment or credentials.");
        }
        String text = uri.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    private static String validClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 200) {
            throw new IllegalArgumentException("Client ID is mandatory (at most 200 characters).");
        }
        return clientId.trim();
    }

    private static String validSecretRef(String reference) {
        if (reference == null || !SECRET_REF.matcher(reference).matches()) {
            throw new IllegalArgumentException("The client secret reference names an environment variable: upper-case letters, digits and underscores, starting with a letter. It is never the secret itself.");
        }
        return reference;
    }

    private static String validScopes(String scopes) {
        String value = scopes == null || scopes.isBlank() ? DEFAULT_SCOPES : scopes.trim();
        if (value.length() > 200 || !SCOPES.matcher(value).matches() || !List.of(value.split(" ")).contains("openid")) {
            throw new IllegalArgumentException("Scopes are space-separated names (at most 200 characters) and must include openid.");
        }
        return value;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable IdentityProvider mutations.");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getIssuer() { return issuer; }
    public String getClientId() { return clientId; }
    public String getClientSecretRef() { return clientSecretRef; }
    public String getScopes() { return scopes; }
    public boolean isAutoProvision() { return autoProvision; }
    public ProviderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    /**
     * <pre>
     * DISABLED -> ACTIVE
     * ACTIVE -> DISABLED
     * </pre>
     */
    public enum ProviderStatus { DISABLED, ACTIVE }
}
