package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.LoginTransaction;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * A sign-in in flight, keyed by its random {@code state} (the document id). The {@code createdAt} field carries the TTL index
 * ({@code StateIndexInitializer}) so an abandoned sign-in disappears on its own. Top-level {@code public} on purpose (see
 * {@link IdentityProviderDocument}).
 */
@Introspected
public class LoginTransactionDocument {

    @BsonId
    private String state;

    private String nonce;
    private String codeVerifier;
    private UUID organisationId;
    private Instant createdAt;

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public String getCodeVerifier() { return codeVerifier; }
    public void setCodeVerifier(String codeVerifier) { this.codeVerifier = codeVerifier; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static LoginTransactionDocument fromDomain(LoginTransaction transaction) {
        LoginTransactionDocument doc = new LoginTransactionDocument();
        doc.setState(transaction.state());
        doc.setNonce(transaction.nonce());
        doc.setCodeVerifier(transaction.codeVerifier());
        doc.setOrganisationId(transaction.organisationId());
        doc.setCreatedAt(transaction.createdAt());
        return doc;
    }

    public LoginTransaction toDomain() {
        return new LoginTransaction(state, nonce, codeVerifier, organisationId, createdAt);
    }
}
