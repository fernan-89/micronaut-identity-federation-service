package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.dto.request.IdentityProviderSettingsRequest;
import com.thinklab.application.dto.request.LinkIdentityRequest;
import com.thinklab.application.usecase.ControlIdentityProviderUseCase;
import com.thinklab.application.usecase.InitiateIdentityProviderUseCase;
import com.thinklab.application.usecase.ControlFederatedLinkUseCase;
import com.thinklab.application.usecase.InitiateFederatedLinkUseCase;
import com.thinklab.domain.exception.InvalidLinkStatusException;
import com.thinklab.domain.repository.FederatedLinkRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The link revoke race against a real MongoDB (platform race audit, ACTION-PLAN 0.5): ten revocations of one federated link at once have exactly one
 * winner. (The hash service is the test double of {@link FederationPersistenceIT}.)
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_identity_federation_race_it";
    private static final String EXECUTOR = "admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject InitiateIdentityProviderUseCase initiateProvider;
    @Inject ControlIdentityProviderUseCase controlProvider;
    @Inject InitiateFederatedLinkUseCase initiateLink;
    @Inject ControlFederatedLinkUseCase controlLink;
    @Inject FederatedLinkRepository links;

    @Test
    @DisplayName("ten revocations of one link at once: exactly one wins, the others are refused with a conflict")
    void tenRevocationsAtOnce() {
        UUID org = UUID.randomUUID();
        var provider = initiateProvider.execute(org, new IdentityProviderSettingsRequest("https://login.example.com", "client", "OIDC_SECRET", null, null), EXECUTOR).block();
        controlProvider.execute(provider.id(), org, ControlIdentityProviderUseCase.Action.ENABLE, EXECUTOR).block();
        var link = initiateLink.execute(org, new LinkIdentityRequest(UUID.randomUUID(), "race-sub"), EXECUTOR).block();

        List<String> results = Flux.range(0, 10)
                .flatMap(i -> controlLink.revoke(link.id(), org, EXECUTOR).thenReturn("OK").onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10)
                .collectList().block(Duration.ofSeconds(30));

        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(9, results.stream().filter(InvalidLinkStatusException.class.getSimpleName()::equals).count(), results.toString());
        assertEquals(2, links.findById(link.id()).block().getAuditTrail().size(), "one entry for linking and one for the single revoke that won");
    }
}
