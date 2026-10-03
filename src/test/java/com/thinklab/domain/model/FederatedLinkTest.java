package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidLinkStatusException;
import com.thinklab.domain.model.FederatedLink.LinkStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedLinkTest {

    private static final String EXECUTOR = "admin";
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    @DisplayName("createNew links a subject to a user as ACTIVE with an INITIATED entry")
    void createNew() {
        FederatedLink link = FederatedLink.createNew(id, org, "https://i.example", "  sub-123  ", user, EXECUTOR);

        assertEquals("sub-123", link.getSubject());
        assertEquals("https://i.example", link.getIssuer());
        assertEquals(user, link.getUserId());
        assertEquals(LinkStatus.ACTIVE, link.getStatus());
        assertEquals("INITIATED", link.getAuditTrail().get(0).action());
    }

    @Test
    @DisplayName("createNew refuses missing ids, issuer, subject and executor")
    void createNewGuards() {
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(null, org, "i", "s", user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, null, "i", "s", user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, null, "s", user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, " ", "s", user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", null, user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", " ", user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", "s".repeat(256), user, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", "s", user, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.createNew(id, org, "i", "s", user, " "));
    }

    @Test
    @DisplayName("revoke is ACTIVE -> REVOKED, terminal, and needs an executor")
    void revoke() {
        FederatedLink link = FederatedLink.createNew(id, org, "i", "s", user, EXECUTOR);
        assertThrows(IllegalArgumentException.class, () -> link.revoke(null));

        AuditEntry entry = link.revoke(EXECUTOR);

        assertEquals(LinkStatus.REVOKED, link.getStatus());
        assertEquals("ACTIVE", entry.fromStatus());
        assertEquals("REVOKED", entry.toStatus());
        assertEquals(2, link.getAuditTrail().size());
        assertThrows(InvalidLinkStatusException.class, () -> link.revoke(EXECUTOR));
        assertThrows(UnsupportedOperationException.class, () -> link.getAuditTrail().clear());
    }

    @Test
    @DisplayName("reconstitute rebuilds the aggregate, tolerating a missing trail, and refuses missing fields")
    void reconstitute() {
        Instant now = Instant.now();
        FederatedLink rebuilt = FederatedLink.reconstitute(id, org, "i", "s", user, LinkStatus.REVOKED, now, now, null);
        assertTrue(rebuilt.getAuditTrail().isEmpty());
        assertEquals(now, rebuilt.getCreatedAt());
        assertEquals(now, rebuilt.getUpdatedAt());
        assertEquals(id, rebuilt.getId());
        assertEquals(org, rebuilt.getOrganisationId());
        AuditEntry entry = new AuditEntry(now, "INITIATED", EXECUTOR, null, "ACTIVE", "d");
        assertEquals(1, FederatedLink.reconstitute(id, org, "i", "s", user, LinkStatus.ACTIVE, now, now, List.of(entry)).getAuditTrail().size());

        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(null, org, "i", "s", user, LinkStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, null, "i", "s", user, LinkStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, null, "s", user, LinkStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, "i", null, user, LinkStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, "i", "s", null, LinkStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, "i", "s", user, null, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, "i", "s", user, LinkStatus.ACTIVE, null, now, null));
        assertThrows(IllegalArgumentException.class, () -> FederatedLink.reconstitute(id, org, "i", "s", user, LinkStatus.ACTIVE, now, null, null));
    }
}
