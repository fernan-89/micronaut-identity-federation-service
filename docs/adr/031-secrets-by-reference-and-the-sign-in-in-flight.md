# ADR-031: Secrets by Reference, the Sign-In in Flight, and What the Indexes Guarantee

## Status
Accepted

## Context
A sign-in involves a client secret, random one-time values and a browser that goes away and comes back. The platform's standing rule is
that no credential is written to a database or a log.

## Decision
- **The client secret is never stored.** An `IdentityProvider` holds `clientSecretRef`, the NAME of an environment variable (upper-case
  letters, digits, underscores - validated, so it cannot be mistaken for the secret itself). The value is read from the environment at the
  moment the token request is made (`ClientSecretPort`; a secret manager would be another adapter), never logged, never returned. If the
  variable is not set the sign-in fails with `503 ERR-FED-00503` and says so.
- **A sign-in in flight is a short-lived, single-use `LoginTransaction`** keyed by a random `state`: the `nonce` the ID token must carry,
  the PKCE `code_verifier` and the organisation. `state`, `nonce` and verifier are each 256 random bits. At the callback the transaction
  is **consumed with one atomic find-and-delete**: a replayed, raced or forged callback finds nothing and is `400 ERR-FED-00400`. A TTL
  index expires abandoned ones, and the callback also refuses one older than `transaction-ttl-seconds` (default 600), so the guarantee
  does not depend on when the TTL monitor runs.
- **The ID token is trusted only if** its signature verifies against the provider's published keys with an allow-listed asymmetric
  algorithm (RS256, PS256, ES256; never `none`, never an HMAC), the issuer and audience match the configuration, it is unexpired, the
  required claims are present and its `nonce` is the one this sign-in generated. Any failure is the same generic `401 ERR-FED-00401`.
- **The provider's endpoints are trusted only over https** (plain http only on a loopback host, for local development), and the discovery
  document must name the configured issuer. An unreachable or untrustworthy provider is `502 ERR-FED-00502`.
- **Indexes are the arbiter**, created at startup and fail-open like the kit's initializer:
  - unique `organisationId` on providers: one per organisation;
  - partial unique `(organisationId, issuer, subject)` and `(organisationId, issuer, userId)` on links, scoped to ACTIVE: an identity maps
    to one user and a user to one identity, while REVOKED links stay as history and free both;
  - TTL on `login_transactions.createdAt`.
- Every administrative route is tenant-scoped; another tenant's provider or link answers `404`.

## Consequences
- Positive: nothing secret at rest; a callback cannot be replayed; an attacker who controls only a provider's response cannot forge a
  session.
- Negative: the operator must provide the secret as an environment variable per provider (a secret-manager adapter is the natural
  follow-up); discovery and key fetches add a round trip per sign-in (no caching in v1).
