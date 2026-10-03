package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidLinkStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Core Domain Model representing the FederatedLink aggregate: the statement "the person the external provider calls
 * {@code subject} is the platform user {@code userId}" within one organisation (ADR-030). It is what makes federation
 * pre-provisioned: signing in through the provider only works for an identity somebody linked.
 *
 * <p>The {@code subject} is the provider's opaque identifier for the person (the OIDC {@code sub} claim), not an email or a name.
 * Nothing else about the external identity is kept.
 *
 * <p>Lifecycle: {@code ACTIVE -> REVOKED} (terminal): unlinking stops the sign-in, and the history stays.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class FederatedLink {

    private final UUID id;
    private final UUID organisationId;
    private final String issuer;
    private final String subject;
    private final UUID userId;
    private LinkStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AuditEntry> auditTrail;

    private FederatedLink(UUID id, UUID organisationId, String issuer, String subject, UUID userId, LinkStatus status,
                          Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.issuer = issuer;
        this.subject = subject;
        this.userId = userId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.auditTrail = auditTrail;
    }

    /** Static factory for aggregate creation (BIAN Behavior Qualifier: {@code link/initiate}). */
    public static FederatedLink createNew(UUID id, UUID organisationId, String issuer, String subject, UUID userId, String executor) {
        if (id == null || organisationId == null || userId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and User ID are mandatory for FederatedLink creation.");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer is mandatory.");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("Subject is mandatory (the provider's identifier for the person, at most 255 characters).");
        }
        requireExecutor(executor);
        Instant now = Instant.now();
        FederatedLink link = new FederatedLink(id, organisationId, issuer, subject.trim(), userId, LinkStatus.ACTIVE, now, now, new ArrayList<>());
        link.auditTrail.add(new AuditEntry(now, "INITIATED", executor, null, LinkStatus.ACTIVE.name(), "Identity linked to user."));
        return link;
    }

    /** Reconstitutes an existing FederatedLink aggregate from the persistence layer. */
    public static FederatedLink reconstitute(UUID id, UUID organisationId, String issuer, String subject, UUID userId, LinkStatus status,
                                             Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        if (id == null || organisationId == null || issuer == null || subject == null || userId == null || status == null
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Every field except the audit trail is mandatory to reconstitute a FederatedLink.");
        }
        return new FederatedLink(id, organisationId, issuer, subject, userId, status, createdAt, updatedAt,
                auditTrail == null ? new ArrayList<>() : new ArrayList<>(auditTrail));
    }

    /** Behavior Qualifier: {@code link/control/revoke}. {@code ACTIVE -> REVOKED} (terminal). */
    public AuditEntry revoke(String executor) {
        requireExecutor(executor);
        if (status != LinkStatus.ACTIVE) {
            throw new InvalidLinkStatusException("Compliance Violation: only an ACTIVE link can be revoked.");
        }
        this.status = LinkStatus.REVOKED;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "STATUS_CHANGED", executor, LinkStatus.ACTIVE.name(), LinkStatus.REVOKED.name(), "Link revoked.");
        auditTrail.add(entry);
        return entry;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable FederatedLink mutations.");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public UUID getUserId() { return userId; }
    public LinkStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    public enum LinkStatus { ACTIVE, REVOKED }
}
