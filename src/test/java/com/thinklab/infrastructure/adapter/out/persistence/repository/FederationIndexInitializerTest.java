package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.application.config.FederationProperties;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class FederationIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);
    private final FederationProperties properties = new FederationProperties();

    private MongoCollection<Document> collectionIn(MongoClient client, MongoDatabase database, String name) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase(any())).thenReturn(database);
        when(database.getCollection(name)).thenReturn(collection);
        return collection;
    }

    @Test
    @DisplayName("startup creates the unique provider index, the two partial unique ACTIVE-link indexes and the TTL index on transactions")
    void createsTheIndexes() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> providers = collectionIn(client, database, "identity_providers");
        MongoCollection<Document> links = collectionIn(client, database, "federated_links");
        MongoCollection<Document> transactions = collectionIn(client, database, "login_transactions");
        when(providers.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("a"));
        when(links.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("b"));
        when(transactions.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("c"));
        properties.setTransactionTtlSeconds(300);

        new FederationIndexInitializer(client, "mongodb://mongo:27017/federation", properties).onApplicationEvent(startup);

        verify(client, org.mockito.Mockito.atLeastOnce()).getDatabase("federation");
        ArgumentCaptor<Document> providerKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> providerOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(providers).createIndex(providerKeys.capture(), providerOptions.capture());
        assertEquals(new Document("organisationId", 1), providerKeys.getValue());
        assertTrue(providerOptions.getValue().isUnique());
        assertEquals(FederationIndexInitializer.PROVIDER_ORGANISATION_INDEX, providerOptions.getValue().getName());

        ArgumentCaptor<Document> linkKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> linkOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(links, times(2)).createIndex(linkKeys.capture(), linkOptions.capture());
        assertEquals(new Document("organisationId", 1).append("issuer", 1).append("subject", 1), linkKeys.getAllValues().get(0));
        assertEquals(new Document("organisationId", 1).append("issuer", 1).append("userId", 1), linkKeys.getAllValues().get(1));
        for (IndexOptions options : linkOptions.getAllValues()) {
            assertTrue(options.isUnique());
            assertEquals(new Document("status", "ACTIVE"), options.getPartialFilterExpression());
        }
        assertEquals(FederationIndexInitializer.LINK_SUBJECT_INDEX, linkOptions.getAllValues().get(0).getName());
        assertEquals(FederationIndexInitializer.LINK_USER_INDEX, linkOptions.getAllValues().get(1).getName());

        ArgumentCaptor<Document> ttlKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> ttlOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(transactions).createIndex(ttlKeys.capture(), ttlOptions.capture());
        assertEquals(new Document("createdAt", 1), ttlKeys.getValue());
        assertEquals(300L, ttlOptions.getValue().getExpireAfter(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        for (String name : new String[]{"identity_providers", "federated_links", "login_transactions"}) {
            when(collectionIn(client, database, name).createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("x"));
        }

        new FederationIndexInitializer(client, "mongodb://mongo:27017", properties).onApplicationEvent(startup);

        verify(client, org.mockito.Mockito.atLeastOnce()).getDatabase(MongoSupport.DEFAULT_DATABASE);
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated, and the next index is still tried")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> providers = collectionIn(client, database, "identity_providers");
        MongoCollection<Document> links = collectionIn(client, database, "federated_links");
        MongoCollection<Document> transactions = collectionIn(client, database, "login_transactions");
        when(providers.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")));
        when(links.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new IllegalStateException("E11000 existing duplicates")));
        when(transactions.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("c"));

        assertDoesNotThrow(() -> new FederationIndexInitializer(client, "mongodb://mongo:27017/f", properties, Duration.ofSeconds(1)).onApplicationEvent(startup));

        verify(transactions).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new FederationIndexInitializer(null, "mongodb://mongo:27017/a", properties));
        assertThrows(NullPointerException.class, () -> new FederationIndexInitializer(client, null, properties));
        assertThrows(NullPointerException.class, () -> new FederationIndexInitializer(client, "mongodb://mongo:27017/a", null));
        assertThrows(NullPointerException.class, () -> new FederationIndexInitializer(client, "mongodb://mongo:27017/a", properties).onApplicationEvent(null));
    }
}
