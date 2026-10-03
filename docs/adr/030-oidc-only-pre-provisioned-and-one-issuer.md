# ADR-030: OIDC Only, Pre-Provisioned, and Still One Issuer of Sessions

## Status
Accepted

## Context
Organisations want their people to sign in with the identity provider they already have (Entra ID, Google, Okta, Keycloak) instead of a
password held by the platform. The platform already has one issuer of tokens (party-authentication, ES256, ADR-021 there) with refresh
rotation, theft detection and revocation built around it, and a standing rule that no credential or personal data is stored beyond what
is needed.

## Decision
- **Protocol: OpenID Connect only** (authorization code flow with PKCE). SAML and SCIM are not built: SAML would roughly double the
  security surface for an audience nobody has asked for yet, and SCIM is provisioning at scale, which a first version does not need. Both
  can be added later as further adapters behind the same ports.
- **The federation service never issues tokens.** After it has authenticated the person, it asks party-authentication to open the
  session (`session/federated`, ADR-022 there), so a federated login is the same session a password login is: same ES256 access token,
  same single-use rotating refresh token, same revocation. No kit change, no second issuer, no new session mechanism.
- Two aggregates in one Service Domain (like `workflow-approval`'s `policy/`):
  - **`IdentityProvider`** (Control Record, root routes): the organisation's OIDC provider - issuer, client id, the NAME of the variable
    holding the client secret, scopes, an `autoProvision` flag. One per organisation. `DISABLED <-> ACTIVE`, starting `DISABLED`; its
    settings are editable only while `DISABLED`, because changing the issuer or client of a provider people sign in through would
    silently change who they are.
  - **`FederatedLink`** (under `/link`): "the person the provider calls `subject` is the platform user `userId`". It is what makes
    federation **pre-provisioned**: signing in only works for an identity an administrator linked. `ACTIVE -> REVOKED` (terminal).
- **Automatic provisioning is off by default.** When an organisation turns it on, a first-time identity whose provider-verified email is
  present gets a new user with the **least privileged role (VIEWER)** and a link. A role is never taken from provider-supplied text
  (groups, claims): that would let whoever controls the provider's attributes grant themselves ADMIN.
- A person who authenticated at the provider but is not linked (and the organisation does not provision) is a generic `403`
  (`ERR-FED-00403`).
- Nothing about the external identity is kept beyond the link's `subject` (the provider's opaque identifier). Email and name live only
  for the length of one call, and only when provisioning needs them. Provider access tokens are never read or stored.

## Consequences
- Positive: SSO with no change to how sessions, roles or revocation work; no one gets in without being invited; the blast radius of a
  compromised provider is a VIEWER.
- Negative: an administrator links each user once (the subject comes from the provider's admin console); SAML customers must wait; two
  concurrent first logins of the same new person while provisioning is on can race (the unique indexes keep one link, the loser sees a
  generic failure and signs in again).
