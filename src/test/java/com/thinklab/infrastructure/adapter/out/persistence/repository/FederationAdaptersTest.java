package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateFederatedLinkException;
import com.thinklab.domain.exception.DuplicateIdentityProviderException;
import com.thinklab.domain.exception.FederatedLinkNotFoundException;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.IdentityProvider.ProviderStatus;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.infrastructure.adapter.out.persistence.entity.FederatedLinkDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.FederatedLinkDocument.FederatedLinkPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.IdentityProviderDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.IdentityProviderDocument.IdentityProviderPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LoginTransactionDocument;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class FederationAdaptersTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    private final MongoClient client = mock(MongoClient.class);
    private final MongoDatabase database = mock(MongoDatabase.class);
    private final UUID id = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    private <T> MongoCollection<T> collectionOf(String name, Class<T> type) {
        MongoCollection<T> collection = mock(MongoCollection.class);
        when(client.getDatabase("fed_db")).thenReturn(database);
        when(database.getCollection(name, type)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        return collection;
    }

    private <T> FindPublisher<T> streaming(MongoCollection<T> collection, T... documents) {
        FindPublisher<T> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<T> subscriber = invocation.getArgument(0);
            Flux.just(documents).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    private IdentityProvider provider() {
        return IdentityProvider.createNew(id, tenant, "https://i.example", "client", "OIDC_SECRET", null, false, "admin");
    }

    private FederatedLink link() {
        return FederatedLink.createNew(id, tenant, "https://i.example", "sub-1", user, "admin");
    }

    // ------------------------------------------------------------------------------------------- IdentityProvider

    @Test
    @DisplayName("provider create inserts the document; a duplicate on the organisation index is DuplicateIdentityProviderException, anything else propagates")
    void providerCreate() {
        MongoCollection<IdentityProviderDocument> collection = collectionOf("identity_providers", IdentityProviderDocument.class);
        MongoWriteException other = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        when(collection.insertOne(any(IdentityProviderDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error index: organisationId_1 dup key")))
                .thenReturn(Mono.error(other));
        IdentityProviderMongoRepositoryAdapter adapter = new IdentityProviderMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.create(provider())).expectNextMatches(saved -> saved.getId().equals(id)).verifyComplete();
        StepVerifier.create(adapter.create(provider())).expectError(DuplicateIdentityProviderException.class).verify();
        StepVerifier.create(adapter.create(provider())).expectErrorMatches(e -> e == other).verify();
    }

    @Test
    @DisplayName("provider finds map the document back by id and organisation, or complete empty, and list the organisation's providers")
    void providerFinds() {
        MongoCollection<IdentityProviderDocument> collection = collectionOf("identity_providers", IdentityProviderDocument.class);
        FindPublisher<IdentityProviderDocument> publisher = streaming(collection, IdentityProviderPersistenceMapper.toDocument(provider()));
        when(publisher.first()).thenReturn(Mono.just(IdentityProviderPersistenceMapper.toDocument(provider())))
                .thenReturn(Mono.just(IdentityProviderPersistenceMapper.toDocument(provider()))).thenReturn(Mono.empty()).thenReturn(Mono.empty());
        IdentityProviderMongoRepositoryAdapter adapter = new IdentityProviderMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.findById(id)).expectNextMatches(p -> p.getClientId().equals("client")).verifyComplete();
        StepVerifier.create(adapter.findByOrganisationId(tenant)).expectNextMatches(p -> p.getId().equals(id)).verifyComplete();
        StepVerifier.create(adapter.findById(id)).verifyComplete();
        StepVerifier.create(adapter.findByOrganisationId(tenant)).verifyComplete();
        StepVerifier.create(adapter.findAllByOrganisationId(tenant)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("provider updates $set their fields and updatedAt and $push the audit entry atomically; a missing provider is IdentityProviderNotFoundException")
    void providerUpdates() {
        MongoCollection<IdentityProviderDocument> collection = collectionOf("identity_providers", IdentityProviderDocument.class);
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        IdentityProvider provider = provider();
        AuditEntry updated = provider.updateSettings("https://j.example", "c2", "OTHER_SECRET", "openid email", true, "admin");
        AuditEntry enabled = provider.enable("admin");
        IdentityProviderMongoRepositoryAdapter adapter = new IdentityProviderMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.updateSettings(id, "https://j.example", "c2", "OTHER_SECRET", "openid email", true, updated)).verifyComplete();
        StepVerifier.create(adapter.updateStatus(id, ProviderStatus.ACTIVE, enabled)).verifyComplete();
        StepVerifier.create(adapter.updateSettings(id, "i", "c", "S", "openid", false, updated)).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(adapter.updateStatus(id, ProviderStatus.ACTIVE, enabled)).expectError(IdentityProviderNotFoundException.class).verify();

        org.mockito.ArgumentCaptor<Bson> update = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).updateOne(any(Bson.class), update.capture());
        BsonDocument settings = render(update.getAllValues().get(0));
        assertEquals("https://j.example", settings.getDocument("$set").getString("issuer").getValue());
        assertEquals("OTHER_SECRET", settings.getDocument("$set").getString("clientSecretRef").getValue());
        assertTrue(settings.getDocument("$set").getBoolean("autoProvision").getValue());
        assertEquals("UPDATED", settings.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
        BsonDocument status = render(update.getAllValues().get(1));
        assertEquals("ACTIVE", status.getDocument("$set").getString("status").getValue());
        assertEquals("STATUS_CHANGED", status.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    // ------------------------------------------------------------------------------------------- FederatedLink

    @Test
    @DisplayName("link create inserts the document; a duplicate on either active-link index is DuplicateFederatedLinkException, anything else propagates")
    void linkCreate() {
        MongoCollection<FederatedLinkDocument> collection = collectionOf("federated_links", FederatedLinkDocument.class);
        MongoWriteException other = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        when(collection.insertOne(any(FederatedLinkDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error index: organisationId_1_issuer_1_subject_1_active dup key")))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error index: organisationId_1_issuer_1_userId_1_active dup key")))
                .thenReturn(Mono.error(other));
        FederatedLinkMongoRepositoryAdapter adapter = new FederatedLinkMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.create(link())).expectNextMatches(saved -> saved.getId().equals(id)).verifyComplete();
        StepVerifier.create(adapter.create(link())).expectError(DuplicateFederatedLinkException.class).verify();
        StepVerifier.create(adapter.create(link())).expectError(DuplicateFederatedLinkException.class).verify();
        StepVerifier.create(adapter.create(link())).expectErrorMatches(e -> e == other).verify();
    }

    @Test
    @DisplayName("link finds map back by id and the ACTIVE identity, or complete empty; the list always filters by tenant and by status only when given")
    void linkFinds() {
        MongoCollection<FederatedLinkDocument> collection = collectionOf("federated_links", FederatedLinkDocument.class);
        FindPublisher<FederatedLinkDocument> publisher = streaming(collection, FederatedLinkPersistenceMapper.toDocument(link()));
        when(publisher.first()).thenReturn(Mono.just(FederatedLinkPersistenceMapper.toDocument(link())))
                .thenReturn(Mono.just(FederatedLinkPersistenceMapper.toDocument(link()))).thenReturn(Mono.empty()).thenReturn(Mono.empty());
        FederatedLinkMongoRepositoryAdapter adapter = new FederatedLinkMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.findById(id)).expectNextMatches(l -> l.getSubject().equals("sub-1")).verifyComplete();
        StepVerifier.create(adapter.findActive(tenant, "https://i.example", "sub-1")).expectNextMatches(l -> l.getUserId().equals(user)).verifyComplete();
        StepVerifier.create(adapter.findById(id)).verifyComplete();
        StepVerifier.create(adapter.findActive(tenant, "https://i.example", "nobody")).verifyComplete();
        StepVerifier.create(adapter.findAllByOrganisationId(tenant, LinkStatus.ACTIVE)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAllByOrganisationId(tenant, null)).expectNextCount(1).verifyComplete();

        org.mockito.ArgumentCaptor<Bson> filters = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(6)).find(filters.capture());
        String active = render(filters.getAllValues().get(1)).toJson();
        assertTrue(active.contains("organisationId") && active.contains("sub-1") && active.contains("ACTIVE") && active.contains("https://i.example"));
        assertTrue(render(filters.getAllValues().get(4)).toJson().contains("ACTIVE"));
        assertTrue(!render(filters.getAllValues().get(5)).toJson().contains("status"));
    }

    @Test
    @DisplayName("link updateStatus $sets the status and pushes the entry; a missing link is FederatedLinkNotFoundException")
    void linkUpdateStatus() {
        MongoCollection<FederatedLinkDocument> collection = collectionOf("federated_links", FederatedLinkDocument.class);
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        FederatedLink link = link();
        AuditEntry revoked = link.revoke("admin");
        FederatedLinkMongoRepositoryAdapter adapter = new FederatedLinkMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.updateStatus(id, LinkStatus.REVOKED, revoked)).verifyComplete();
        StepVerifier.create(adapter.updateStatus(id, LinkStatus.REVOKED, revoked)).expectError(FederatedLinkNotFoundException.class).verify();

        org.mockito.ArgumentCaptor<Bson> update = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getAllValues().get(0));
        assertEquals("REVOKED", doc.getDocument("$set").getString("status").getValue());
        assertEquals("REVOKED", doc.getDocument("$push").getDocument("auditTrail").getString("toStatus").getValue());
    }

    // ------------------------------------------------------------------------------------------- LoginTransaction

    @Test
    @DisplayName("a transaction is inserted by its state and consumed by one atomic find-and-delete; an unknown state completes empty")
    void transactions() {
        MongoCollection<LoginTransactionDocument> collection = collectionOf("login_transactions", LoginTransactionDocument.class);
        LoginTransaction transaction = new LoginTransaction("state-1", "nonce-1", "verifier-1", tenant, Instant.parse("2026-10-03T12:00:00Z"));
        when(collection.insertOne(any(LoginTransactionDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        when(collection.findOneAndDelete(any(Bson.class))).thenReturn(Mono.just(LoginTransactionDocument.fromDomain(transaction))).thenReturn(Mono.empty());
        LoginTransactionMongoRepositoryAdapter adapter = new LoginTransactionMongoRepositoryAdapter(client, "mongodb://localhost/fed_db");

        StepVerifier.create(adapter.save(transaction)).verifyComplete();
        StepVerifier.create(adapter.consume("state-1")).expectNext(transaction).verifyComplete();
        StepVerifier.create(adapter.consume("state-1")).verifyComplete();

        org.mockito.ArgumentCaptor<LoginTransactionDocument> saved = org.mockito.ArgumentCaptor.forClass(LoginTransactionDocument.class);
        verify(collection).insertOne(saved.capture());
        assertEquals("state-1", saved.getValue().getState());
        org.mockito.ArgumentCaptor<Bson> filter = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).findOneAndDelete(filter.capture());
        assertEquals("state-1", render(filter.getAllValues().get(0)).getString("_id").getValue());
    }
}
