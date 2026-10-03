package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the IdentityProvider aggregate for MongoDB. Top-level {@code public} on purpose: a
 * package-private BSON entity passes every mocked test but fails on the first real write. There is no secret field: only the NAME
 * of the variable that holds it (ADR-031).
 */
@Introspected
public class IdentityProviderDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String issuer;
    private String clientId;
    private String clientSecretRef;
    private String scopes;
    private boolean autoProvision;
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
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecretRef() { return clientSecretRef; }
    public void setClientSecretRef(String clientSecretRef) { this.clientSecretRef = clientSecretRef; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public boolean isAutoProvision() { return autoProvision; }
    public void setAutoProvision(boolean autoProvision) { this.autoProvision = autoProvision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    /** Strict isolation between Document and Domain. */
    public static final class IdentityProviderPersistenceMapper {

        private IdentityProviderPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static IdentityProviderDocument toDocument(IdentityProvider provider) {
            IdentityProviderDocument doc = new IdentityProviderDocument();
            doc.setId(provider.getId());
            doc.setOrganisationId(provider.getOrganisationId());
            doc.setIssuer(provider.getIssuer());
            doc.setClientId(provider.getClientId());
            doc.setClientSecretRef(provider.getClientSecretRef());
            doc.setScopes(provider.getScopes());
            doc.setAutoProvision(provider.isAutoProvision());
            doc.setStatus(provider.getStatus().name());
            doc.setCreatedAt(provider.getCreatedAt());
            doc.setUpdatedAt(provider.getUpdatedAt());
            doc.setAuditTrail(provider.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static IdentityProvider toDomain(IdentityProviderDocument doc) {
            return IdentityProvider.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getIssuer(), doc.getClientId(), doc.getClientSecretRef(),
                    doc.getScopes(), doc.isAutoProvision(), ProviderStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).toList());
        }
    }
}
