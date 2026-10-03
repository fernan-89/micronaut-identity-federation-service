package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.domain.repository.LoginTransactionRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LoginTransactionDocument;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/**
 * MongoDB Reactive Repository Adapter for the sign-ins in flight. {@code consume} is a single {@code findOneAndDelete}: reading and
 * deleting are one atomic step, so a callback replayed (or raced) by anyone finds nothing the second time. The TTL index
 * ({@link FederationIndexInitializer}) removes the transactions nobody came back for.
 */
@Singleton
public class LoginTransactionMongoRepositoryAdapter implements LoginTransactionRepository {

    private final MongoClient mongoClient;
    private final String database;

    public LoginTransactionMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<LoginTransactionDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.TRANSACTIONS_COLLECTION, LoginTransactionDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Void> save(LoginTransaction transaction) {
        return Mono.from(collection().insertOne(LoginTransactionDocument.fromDomain(transaction))).then();
    }

    @Override
    public Mono<LoginTransaction> consume(String state) {
        return Mono.from(collection().findOneAndDelete(Filters.eq("_id", state))).map(LoginTransactionDocument::toDomain);
    }
}
