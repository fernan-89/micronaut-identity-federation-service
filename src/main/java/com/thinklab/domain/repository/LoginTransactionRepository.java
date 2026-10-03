package com.thinklab.domain.repository;

import com.thinklab.domain.model.LoginTransaction;
import reactor.core.publisher.Mono;

/** Outbound Port for the sign-ins in flight (ADR-031): saved at initiation, consumed exactly once at the callback. */
public interface LoginTransactionRepository {

    Mono<Void> save(LoginTransaction transaction);

    /** Reads and deletes the transaction in one atomic step; empty when the state is unknown or was already consumed. */
    Mono<LoginTransaction> consume(String state);
}
