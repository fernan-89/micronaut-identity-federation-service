package com.thinklab.application.usecase;

import com.thinklab.application.config.FederationProperties;
import com.thinklab.domain.exception.FederationAuthenticationException;
import com.thinklab.domain.exception.IdentityNotLinkedException;
import com.thinklab.domain.exception.IdentityProviderNotFoundException;
import com.thinklab.domain.exception.InvalidLoginStateException;
import com.thinklab.domain.exception.ProviderMisconfiguredException;
import com.thinklab.domain.exception.ProviderNotAvailableException;
import com.thinklab.domain.model.FederatedLink;
import com.thinklab.domain.model.IdentityClaims;
import com.thinklab.domain.model.IdentityProvider;
import com.thinklab.domain.model.LoginTransaction;
import com.thinklab.domain.port.ClientSecretPort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.OidcClientPort;
import com.thinklab.domain.port.PartyAuthenticationPort;
import com.thinklab.domain.port.PartyAuthenticationPort.FederatedSession;
import com.thinklab.domain.repository.FederatedLinkRepository;
import com.thinklab.domain.repository.IdentityProviderRepository;
import com.thinklab.domain.repository.LoginTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REDIRECT = "http://localhost:5173/api/identity-federation/v1/login/callback";

    @Mock private IdentityProviderRepository providers;
    @Mock private LoginTransactionRepository transactions;
    @Mock private FederatedLinkRepository links;
    @Mock private ClientSecretPort secrets;
    @Mock private OidcClientPort oidc;
    @Mock private PartyAuthenticationPort party;
    @Mock private HashServicePort hashServicePort;
    @Mock private LoginTokens tokens;

    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final FederationProperties properties = new FederationProperties();
    private InitiateLoginUseCase initiate;
    private CompleteLoginUseCase complete;

    @BeforeEach
    void setUp() {
        properties.setRedirectUri(REDIRECT);
        initiate = new InitiateLoginUseCase(providers, transactions, oidc, tokens, properties, CLOCK);
        complete = new CompleteLoginUseCase(transactions, providers, links, secrets, oidc, party, hashServicePort, properties, CLOCK);
    }

    private IdentityProvider provider(boolean enabled, boolean autoProvision) {
        IdentityProvider provider = IdentityProvider.createNew(UUID.randomUUID(), org, "https://i.example", "client", "OIDC_SECRET", null, autoProvision, "admin");
        if (enabled) {
            provider.enable("admin");
        }
        return provider;
    }

    private LoginTransaction transaction(Instant createdAt) {
        return new LoginTransaction("state-1", "nonce-1", "verifier-1", org, createdAt);
    }

    private final FederatedSession session = new FederatedSession("access", "Bearer", 600, "refresh", 3600);

    // --------------------------------------------------------------------------------------- LoginTokens

    @Test
    @DisplayName("random values are 43 URL-safe characters and never repeat; the challenge is the RFC 7636 S256 transform of the verifier")
    void loginTokens() {
        LoginTokens real = new LoginTokens();

        String first = real.next();
        assertEquals(43, first.length());
        assertEquals(true, first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, real.next());
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", real.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertThrows(IllegalStateException.class, () -> real.challengeFor("x", "NO-SUCH-DIGEST"));
    }

    // --------------------------------------------------------------------------------------- InitiateLoginUseCase

    @Test
    @DisplayName("initiating stores a single-use transaction, THEN asks for the authorization URL with the PKCE challenge, and answers that URL")
    void initiateLogin() {
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, false)));
        when(tokens.next()).thenReturn("state-1", "nonce-1", "verifier-1");
        when(tokens.challengeFor("verifier-1")).thenReturn("challenge-1");
        when(transactions.save(any(LoginTransaction.class))).thenReturn(Mono.empty());
        when(oidc.authorizationUrl(any(IdentityProvider.class), eq(REDIRECT), eq("state-1"), eq("nonce-1"), eq("challenge-1")))
                .thenReturn(Mono.just("https://i.example/authorize?x=1"));

        StepVerifier.create(initiate.execute(org)).expectNext("https://i.example/authorize?x=1").verifyComplete();

        ArgumentCaptor<LoginTransaction> saved = ArgumentCaptor.forClass(LoginTransaction.class);
        verify(transactions).save(saved.capture());
        assertEquals(new LoginTransaction("state-1", "nonce-1", "verifier-1", org, NOW), saved.getValue());
    }

    @Test
    @DisplayName("initiating is refused for an organisation without a provider or with a DISABLED one, storing nothing")
    void initiateRefusals() {
        when(providers.findByOrganisationId(org)).thenReturn(Mono.empty()).thenReturn(Mono.just(provider(false, false)));

        StepVerifier.create(initiate.execute(org)).expectError(IdentityProviderNotFoundException.class).verify();
        StepVerifier.create(initiate.execute(org)).expectError(ProviderNotAvailableException.class).verify();
        verify(transactions, never()).save(any());
    }

    @Test
    @DisplayName("the public constructor uses the system clock")
    void initiatePublicConstructor() {
        when(providers.findByOrganisationId(org)).thenReturn(Mono.empty());

        StepVerifier.create(new InitiateLoginUseCase(providers, transactions, oidc, tokens, properties).execute(org))
                .expectError(IdentityProviderNotFoundException.class).verify();
    }

    // --------------------------------------------------------------------------------------- CompleteLoginUseCase

    private void givenAuthenticatedAs(IdentityClaims claims) {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW.minusSeconds(30))));
        when(secrets.resolve("OIDC_SECRET")).thenReturn(Optional.of("the-secret"));
        when(oidc.authenticate(any(IdentityProvider.class), eq("the-secret"), eq(REDIRECT), eq("code-1"), eq("verifier-1"), eq("nonce-1")))
                .thenReturn(Mono.just(claims));
    }

    @Test
    @DisplayName("a linked identity gets a session from party-authentication; the response carries its credentials")
    void completeLinked() {
        givenAuthenticatedAs(new IdentityClaims("sub-1", "ada@example.com", true, "Ada"));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, false)));
        FederatedLink link = FederatedLink.createNew(UUID.randomUUID(), org, "https://i.example", "sub-1", user, "admin");
        when(links.findActive(org, "https://i.example", "sub-1")).thenReturn(Mono.just(link));
        when(party.openSession(org, user)).thenReturn(Mono.just(session));

        StepVerifier.create(complete.execute("code-1", "state-1")).assertNext(response -> {
            assertEquals("access", response.accessToken());
            assertEquals("refresh", response.refreshToken());
            assertEquals(600, response.expiresIn());
            assertEquals(3600, response.refreshExpiresIn());
        }).verifyComplete();
        verify(party, never()).provisionViewer(any(), any(), any());
    }

    @Test
    @DisplayName("an identity nobody linked is refused (403) when the organisation does not provision, or the email is missing or unverified")
    void completeNotLinked() {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW)));
        when(secrets.resolve("OIDC_SECRET")).thenReturn(Optional.of("the-secret"));
        when(oidc.authenticate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new IdentityClaims("sub-9", "ada@example.com", true, "Ada")))
                .thenReturn(Mono.just(new IdentityClaims("sub-9", null, true, "Ada")))
                .thenReturn(Mono.just(new IdentityClaims("sub-9", "ada@example.com", false, "Ada")));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, false)))
                .thenReturn(Mono.just(provider(true, true)))
                .thenReturn(Mono.just(provider(true, true)));
        when(links.findActive(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(IdentityNotLinkedException.class).verify();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(IdentityNotLinkedException.class).verify();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(IdentityNotLinkedException.class).verify();
        verify(party, never()).provisionViewer(any(), any(), any());
        verify(party, never()).openSession(any(), any());
    }

    @Test
    @DisplayName("when the organisation provisions and the provider vouches for the email, a first-time identity gets a VIEWER and a link, then a session")
    void completeProvisions() {
        givenAuthenticatedAs(new IdentityClaims("sub-new", "grace@example.com", true, "Grace Hopper"));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, true)));
        when(links.findActive(org, "https://i.example", "sub-new")).thenReturn(Mono.empty());
        when(party.provisionViewer(org, "grace@example.com", "Grace Hopper")).thenReturn(Mono.just(user));
        when(hashServicePort.generateSovereignId("federated-link-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(links.create(any(FederatedLink.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(party.openSession(org, user)).thenReturn(Mono.just(session));

        StepVerifier.create(complete.execute("code-1", "state-1")).assertNext(r -> assertEquals("access", r.accessToken())).verifyComplete();

        ArgumentCaptor<FederatedLink> created = ArgumentCaptor.forClass(FederatedLink.class);
        verify(links).create(created.capture());
        assertEquals("sub-new", created.getValue().getSubject());
        assertEquals(user, created.getValue().getUserId());
        assertEquals("https://i.example", created.getValue().getIssuer());
        assertEquals("identity-federation", created.getValue().getAuditTrail().get(0).executor());
    }

    @Test
    @DisplayName("provisioning names the user after the email's local part when the provider sent no name")
    void completeProvisionsWithoutAName() {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW)));
        when(secrets.resolve("OIDC_SECRET")).thenReturn(Optional.of("the-secret"));
        when(oidc.authenticate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new IdentityClaims("sub-a", "ada@example.com", true, null)))
                .thenReturn(Mono.just(new IdentityClaims("sub-b", "bob@example.com", true, "  ")));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, true)));
        when(links.findActive(any(), any(), any())).thenReturn(Mono.empty());
        when(party.provisionViewer(any(), any(), any())).thenReturn(Mono.just(user));
        when(hashServicePort.generateSovereignId("federated-link-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(links.create(any(FederatedLink.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(party.openSession(org, user)).thenReturn(Mono.just(session));

        StepVerifier.create(complete.execute("code-1", "state-1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectNextCount(1).verifyComplete();

        verify(party).provisionViewer(org, "ada@example.com", "ada");
        verify(party).provisionViewer(org, "bob@example.com", "bob");
    }

    @Test
    @DisplayName("an unknown, replayed or expired state is refused (400) before anything else happens")
    void completeBadState() {
        when(transactions.consume("unknown")).thenReturn(Mono.empty());
        when(transactions.consume("old")).thenReturn(Mono.just(transaction(NOW.minusSeconds(601))));

        StepVerifier.create(complete.execute("code-1", "unknown")).expectError(InvalidLoginStateException.class).verify();
        StepVerifier.create(complete.execute("code-1", "old")).expectError(InvalidLoginStateException.class).verify();
        verify(oidc, never()).authenticate(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a transaction right at the limit is still valid")
    void completeAtTheLimit() {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW.minusSeconds(600))));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.empty());

        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(ProviderNotAvailableException.class).verify();
    }

    @Test
    @DisplayName("a provider that vanished or was disabled mid-flight, and a client secret the server does not have, are refused")
    void completeProviderProblems() {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW)));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.empty()).thenReturn(Mono.just(provider(false, false))).thenReturn(Mono.just(provider(true, false)));
        when(secrets.resolve("OIDC_SECRET")).thenReturn(Optional.empty());

        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(ProviderNotAvailableException.class).verify();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(ProviderNotAvailableException.class).verify();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(ProviderMisconfiguredException.class).verify();
        verify(oidc, never()).authenticate(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a provider answer that cannot be trusted, and a user party-authentication refuses, end the sign-in without a session")
    void completeUntrusted() {
        when(transactions.consume("state-1")).thenReturn(Mono.just(transaction(NOW)));
        when(providers.findByOrganisationId(org)).thenReturn(Mono.just(provider(true, false)));
        when(secrets.resolve("OIDC_SECRET")).thenReturn(Optional.of("the-secret"));
        when(oidc.authenticate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new FederationAuthenticationException("bad token")))
                .thenReturn(Mono.just(new IdentityClaims("sub-1", "ada@example.com", true, "Ada")));
        FederatedLink link = FederatedLink.createNew(UUID.randomUUID(), org, "https://i.example", "sub-1", user, "admin");
        when(links.findActive(any(), any(), any())).thenReturn(Mono.just(link));
        when(party.openSession(org, user)).thenReturn(Mono.error(new FederationAuthenticationException("refused")));

        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(FederationAuthenticationException.class).verify();
        StepVerifier.create(complete.execute("code-1", "state-1")).expectError(FederationAuthenticationException.class).verify();
    }

    @Test
    @DisplayName("the public constructor uses the system clock")
    void completePublicConstructor() {
        when(transactions.consume("x")).thenReturn(Mono.empty());

        StepVerifier.create(new CompleteLoginUseCase(transactions, providers, links, secrets, oidc, party, hashServicePort, properties).execute("c", "x"))
                .expectError(InvalidLoginStateException.class).verify();
    }
}
