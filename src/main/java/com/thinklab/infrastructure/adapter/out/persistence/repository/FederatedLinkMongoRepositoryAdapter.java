package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateFederatedLinkException;
import com.thinklab.domain.exception.FederatedLinkNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.FederatedLinkDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.FederatedLinkDocument.FederatedLinkPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for FederatedLinks. Every change is one atomic {@code $set} + {@code $push} that also appends
 * the audit entry. Two partial unique indexes over ACTIVE links ({@link FederationIndexInitializer}) keep an external identity to one
 * user and a user to one identity per issuer, while any number of REVOKED links stay as history.
 */
@Singleton
public class FederatedLinkMongoRepositoryAdapter implements FederatedLinkRepository {

    private final MongoClient mongoClient;
    private final String database;

    public FederatedLinkMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<FederatedLinkDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.LINKS_COLLECTION, FederatedLinkDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<FederatedLink> create(FederatedLink link) {
        return Mono.from(collection().insertOne(FederatedLinkPersistenceMapper.toDocument(link)))
                .map(result -> link)
                .onErrorMap(e -> MongoSupport.isDuplicateOn(e, FederationIndexInitializer.LINK_SUBJECT_INDEX)
                                || MongoSupport.isDuplicateOn(e, FederationIndexInitializer.LINK_USER_INDEX),
                        e -> new DuplicateFederatedLinkException("This identity, or this user, is already linked."));
    }

    @Override
    public Mono<FederatedLink> findById(UUID id) {
        return Mono.from(collection().find(Filters.eq("_id", id)).first()).map(FederatedLinkPersistenceMapper::toDomain);
    }

    @Override
    public Mono<FederatedLink> findActive(UUID organisationId, String issuer, String subject) {
        Bson filter = Filters.and(Filters.eq("organisationId", organisationId), Filters.eq("issuer", issuer), Filters.eq("subject", subject),
                Filters.eq("status", LinkStatus.ACTIVE.name()));
        return Mono.from(collection().find(filter).first()).map(FederatedLinkPersistenceMapper::toDomain);
    }

    @Override
    public Flux<FederatedLink> findAllByOrganisationId(UUID organisationId, LinkStatus status) {
        Bson filter = status == null
                ? Filters.eq("organisationId", organisationId)
                : Filters.and(Filters.eq("organisationId", organisationId), Filters.eq("status", status.name()));
        return Flux.from(collection().find(filter).sort(Sorts.descending("createdAt"))).map(FederatedLinkPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateStatus(UUID id, LinkStatus status, AuditEntry auditEntry) {
        Bson update = Updates.combine(Updates.set("status", status.name()), Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry)));
        return Mono.from(collection().updateOne(Filters.eq("_id", id), update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new FederatedLinkNotFoundException("Federated link " + id + " could not be found."))
                        : Mono.empty());
    }
}
