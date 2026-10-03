package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.application.config.FederationProperties;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates, at startup, the indexes the domain rules lean on (ADR-031):
 * <ul>
 *   <li>unique {@code organisationId} on {@code identity_providers}: one provider per organisation;</li>
 *   <li>partial unique {@code (organisationId, issuer, subject)} and {@code (organisationId, issuer, userId)} on
 *       {@code federated_links}, scoped to ACTIVE links: an external identity maps to one user and a user to one identity, while any
 *       number of REVOKED links stay as history;</li>
 *   <li>TTL on {@code login_transactions.createdAt}: a sign-in nobody came back for expires on its own.</li>
 * </ul>
 * Fail-open like the kit's initializer: errors are logged and the application still starts.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class FederationIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String PROVIDER_ORGANISATION_INDEX = "organisationId_1";
    static final String LINK_SUBJECT_INDEX = "organisationId_1_issuer_1_subject_1_active";
    static final String LINK_USER_INDEX = "organisationId_1_issuer_1_userId_1_active";
    static final String TRANSACTION_TTL_INDEX = "createdAt_1_ttl";

    private static final Logger log = LoggerFactory.getLogger(FederationIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final FederationProperties properties;
    private final Duration timeout;

    @Inject
    public FederationIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri, FederationProperties properties) {
        this(mongoClient, mongoUri, properties, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    FederationIndexInitializer(MongoClient mongoClient, String mongoUri, FederationProperties properties, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        this.database = MongoSupport.database(mongoUri);
        this.properties = Objects.requireNonNull(properties, "Infrastructure constraint violated: properties cannot be null.");
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        ensure(MongoSupport.PROVIDERS_COLLECTION, new Document("organisationId", 1), new IndexOptions().unique(true).name(PROVIDER_ORGANISATION_INDEX));
        Document active = new Document("status", LinkStatus.ACTIVE.name());
        ensure(MongoSupport.LINKS_COLLECTION, new Document("organisationId", 1).append("issuer", 1).append("subject", 1),
                new IndexOptions().unique(true).name(LINK_SUBJECT_INDEX).partialFilterExpression(active));
        ensure(MongoSupport.LINKS_COLLECTION, new Document("organisationId", 1).append("issuer", 1).append("userId", 1),
                new IndexOptions().unique(true).name(LINK_USER_INDEX).partialFilterExpression(active));
        ensure(MongoSupport.TRANSACTIONS_COLLECTION, new Document("createdAt", 1),
                new IndexOptions().name(TRANSACTION_TTL_INDEX).expireAfter(properties.getTransactionTtlSeconds(), TimeUnit.SECONDS));
    }

    private void ensure(String collection, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options)).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", options.getName(), database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", options.getName(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", options.getName(), database, collection, e.getMessage());
        }
    }
}
