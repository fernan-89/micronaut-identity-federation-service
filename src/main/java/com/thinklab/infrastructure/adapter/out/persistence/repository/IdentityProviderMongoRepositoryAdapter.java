package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateIdentityProviderException;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.repository.IdentityProviderRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.IdentityProviderDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.IdentityProviderDocument.IdentityProviderPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for IdentityProviders. Every change is one atomic {@code $set} + {@code $push} that also
 * appends the audit entry. The unique {@code organisationId} index ({@link FederationIndexInitializer}) is what keeps it to one
 * provider per organisation, even under concurrent registration.
 */
@Singleton
public class IdentityProviderMongoRepositoryAdapter implements IdentityProviderRepository {

    private final MongoClient mongoClient;
    private final String database;

    public IdentityProviderMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<IdentityProviderDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.PROVIDERS_COLLECTION, IdentityProviderDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<IdentityProvider> create(IdentityProvider provider) {
        return Mono.from(collection().insertOne(IdentityProviderPersistenceMapper.toDocument(provider)))
                .map(result -> provider)
                .onErrorMap(e -> MongoSupport.isDuplicateOn(e, FederationIndexInitializer.PROVIDER_ORGANISATION_INDEX),
                        e -> new DuplicateIdentityProviderException("Organisation " + provider.getOrganisationId() + " already has an identity provider."));
    }

    @Override
    public Mono<IdentityProvider> findById(UUID id) {
        return Mono.from(collection().find(Filters.eq("_id", id)).first()).map(IdentityProviderPersistenceMapper::toDomain);
    }

    @Override
    public Mono<IdentityProvider> findByOrganisationId(UUID organisationId) {
        return Mono.from(collection().find(Filters.eq("organisationId", organisationId)).first()).map(IdentityProviderPersistenceMapper::toDomain);
    }

    @Override
    public Flux<IdentityProvider> findAllByOrganisationId(UUID organisationId) {
        return Flux.from(collection().find(Filters.eq("organisationId", organisationId))).map(IdentityProviderPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateSettings(UUID id, String issuer, String clientId, String clientSecretRef, String scopes, boolean autoProvision,
                                     AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("issuer", issuer),
                Updates.set("clientId", clientId),
                Updates.set("clientSecretRef", clientSecretRef),
                Updates.set("scopes", scopes),
                Updates.set("autoProvision", autoProvision),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    @Override
    public Mono<Void> updateStatus(UUID id, ProviderStatus status, AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("status", status.name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(collection().updateOne(Filters.eq("_id", id), update))
                .flatMap(result -> result.getMatchedCount() == 0 ? Mono.error(new IdentityProviderNotFoundException(id)) : Mono.empty());
    }
}
