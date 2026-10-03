package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the FederatedLink aggregate for MongoDB (top-level {@code public}, see {@link IdentityProviderDocument}). */
@Introspected
public class FederatedLinkDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String issuer;
    private String subject;
    private UUID userId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    /** Strict isolation between Document and Domain. */
    public static final class FederatedLinkPersistenceMapper {

        private FederatedLinkPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static FederatedLinkDocument toDocument(FederatedLink link) {
            FederatedLinkDocument doc = new FederatedLinkDocument();
            doc.setId(link.getId());
            doc.setOrganisationId(link.getOrganisationId());
            doc.setIssuer(link.getIssuer());
            doc.setSubject(link.getSubject());
            doc.setUserId(link.getUserId());
            doc.setStatus(link.getStatus().name());
            doc.setCreatedAt(link.getCreatedAt());
            doc.setUpdatedAt(link.getUpdatedAt());
            doc.setAuditTrail(link.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static FederatedLink toDomain(FederatedLinkDocument doc) {
            return FederatedLink.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getIssuer(), doc.getSubject(), doc.getUserId(),
                    LinkStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).toList());
        }
    }
}
