package com.thinklab.infrastructure.adapter.out.integration.partyauthentication;

import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.port.PartyAuthenticationPort.FederatedSession;
import com.thinklab.infrastructure.adapter.out.integration.partyauthentication.PartyAuthenticationServiceAdapter.FederatedSessionApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.partyauthentication.PartyAuthenticationServiceAdapter.InitiateUserApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.partyauthentication.PartyAuthenticationServiceAdapter.SessionApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.partyauthentication.PartyAuthenticationServiceAdapter.UserApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyAuthenticationServiceAdapterTest {

    private final PartyAuthenticationApiClient api = mock(PartyAuthenticationApiClient.class);
    private final PartyAuthenticationServiceAdapter adapter = new PartyAuthenticationServiceAdapter(api);
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    @DisplayName("openSession asks party-authentication for a federated session and relays the credentials")
    void openSession() {
        when(api.federated(any(FederatedSessionApiRequest.class))).thenReturn(Mono.just(new SessionApiResponse("access", "Bearer", 600, "refresh", 3600)));

        StepVerifier.create(adapter.openSession(org, user)).expectNext(new FederatedSession("access", "Bearer", 600, "refresh", 3600)).verifyComplete();

        ArgumentCaptor<FederatedSessionApiRequest> sent = ArgumentCaptor.forClass(FederatedSessionApiRequest.class);
        verify(api).federated(sent.capture());
        assertEquals(new FederatedSessionApiRequest(org, user), sent.getValue());
    }

    @Test
    @DisplayName("any failure while opening the session is the same generic FederationAuthenticationException")
    void openSessionFailure() {
        when(api.federated(any(FederatedSessionApiRequest.class))).thenReturn(Mono.error(new IllegalStateException("401 from party-authentication")));

        StepVerifier.create(adapter.openSession(org, user)).expectErrorMatches(e -> e instanceof FederationAuthenticationException
                && !e.getMessage().contains("401")).verify();
    }

    @Test
    @DisplayName("provisionViewer creates a VIEWER (the least privileged role) as the federation executor, activates it and answers its id")
    void provisionViewer() {
        when(api.initiateUser(eq(org.toString()), eq("identity-federation"), any(InitiateUserApiRequest.class))).thenReturn(Mono.just(new UserApiResponse(user)));
        when(api.activateUser(user, org.toString(), "identity-federation")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.provisionViewer(org, "grace@example.com", "Grace")).expectNext(user).verifyComplete();

        ArgumentCaptor<InitiateUserApiRequest> sent = ArgumentCaptor.forClass(InitiateUserApiRequest.class);
        verify(api).initiateUser(eq(org.toString()), eq("identity-federation"), sent.capture());
        assertEquals(new InitiateUserApiRequest("Grace", "grace@example.com", "VIEWER"), sent.getValue());
    }

    @Test
    @DisplayName("a failure creating or activating the user is the same generic FederationAuthenticationException, and an unactivated user is never answered")
    void provisionFailures() {
        when(api.initiateUser(any(), any(), any(InitiateUserApiRequest.class))).thenReturn(Mono.error(new IllegalStateException("duplicate")))
                .thenReturn(Mono.just(new UserApiResponse(user)));
        when(api.activateUser(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(adapter.provisionViewer(org, "a@example.com", "A")).expectError(FederationAuthenticationException.class).verify();
        StepVerifier.create(adapter.provisionViewer(org, "a@example.com", "A")).expectError(FederationAuthenticationException.class).verify();
        verify(api, never()).federated(any());
    }
}
