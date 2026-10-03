# micronaut-identity-federation-service

BIAN-aligned Service Domain **identity-federation** (Control Record: `IdentityProvider`), port `8097`.

Sign in through the identity provider an organisation already has (Entra ID, Google, Okta, Keycloak...) with OpenID Connect, instead of
a password the platform holds. It authenticates the person at the provider, then asks party-authentication to open the session: a
federated login is **the same session** a password login is (same ES256 access token, same single-use rotating refresh token, same
revocation). Nothing here issues a token (ADR-030).

## What it guarantees, and what it does not

- **Pre-provisioned.** Only an identity an administrator linked to a user can sign in. Automatic provisioning is off by default; when
  on, a first-time identity with a provider-verified email gets a new **VIEWER**, never a role taken from the provider's attributes.
- **No secret at rest.** An identity provider stores the NAME of the environment variable that holds its client secret, never the
  secret. A missing variable fails the sign-in with 503 (ADR-031).
- **A callback cannot be replayed.** Each sign-in is a single-use transaction (random state, nonce, PKCE verifier) consumed by one atomic
  find-and-delete; abandoned ones expire (ADR-031).
- **The ID token is only trusted** when its signature verifies against the provider's published keys with RS256, PS256 or ES256, and the
  issuer, audience, expiry and nonce match. Endpoints must be https (http only on localhost, for development).
- **OIDC only.** No SAML, no SCIM, no events (ADR-030, ADR-032). Nothing about the external identity is kept beyond the link's `subject`.

## BIAN Behavior Qualifier Contract

Administration is tenant-scoped by `X-Tenant-Id` and needs `X-Executor` on every change (and a token with the administrator role when
security is on). The two `login` routes are public: the person has no token yet.

### IdentityProvider - `/identity-federation/v1`

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /identity-federation/v1/initiate` `{"issuer":"https://login.example.com","clientId":"...","clientSecretRef":"OIDC_ACME_SECRET","scopes":"openid email profile","autoProvision":false}` (starts DISABLED) |
| retrieve | `GET /identity-federation/v1/{id}/retrieve` |
| retrieve (collection) | `GET /identity-federation/v1/retrieve` (the tenant's provider, at most one) |
| update | `PUT /identity-federation/v1/{id}/update` (only while DISABLED) |
| control/enable | `PUT /identity-federation/v1/{id}/control/enable` (DISABLED -> ACTIVE) |
| control/disable | `PUT /identity-federation/v1/{id}/control/disable` (ACTIVE -> DISABLED) |
| audit-log/retrieve | `GET /identity-federation/v1/{id}/audit-log/retrieve` |

### FederatedLink - `/identity-federation/v1/link`

| Behavior Qualifier | Route |
|---|---|
| link/initiate | `POST /identity-federation/v1/link/initiate` `{"userId":"<platform user>","subject":"<the provider's id for the person (OIDC sub)>"}` |
| link/retrieve | `GET /identity-federation/v1/link/{id}/retrieve` |
| link/retrieve (collection) | `GET /identity-federation/v1/link/retrieve?status=` |
| link/control/revoke | `PUT /identity-federation/v1/link/{id}/control/revoke` (ACTIVE -> REVOKED, terminal) |

### Sign-in - `/identity-federation/v1/login` (public)

| Behavior Qualifier | Route |
|---|---|
| login/initiate | `GET /identity-federation/v1/login/initiate?organisationId=` answers `302` to the provider |
| login/callback | `GET /identity-federation/v1/login/callback?code=&state=` answers the session JSON (the platform gateway turns it into an HttpOnly cookie and a redirect, gateway ADR-025) |

Register `THINKLAB_FEDERATION_REDIRECT_URI` (the callback as the browser sees it) at the provider as an allowed redirect URI.

## Configuration

| Setting | Env | Default | Meaning |
|---|---|---|---|
| `thinklab.federation.redirect-uri` | `THINKLAB_FEDERATION_REDIRECT_URI` | `http://localhost:5173/api/identity-federation/v1/login/callback` | The callback URL as the browser sees it |
| `thinklab.federation.transaction-ttl-seconds` | `THINKLAB_FEDERATION_TRANSACTION_TTL_SECONDS` | `600` | How long a started sign-in may take |
| *(per provider)* `clientSecretRef` | the variable it names | - | The client secret itself, read at the moment of use |

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-FED-00400` | 400 | The sign-in state is unknown, already used or expired |
| `ERR-FED-00401` | 401 | The provider refused, or its answer could not be trusted (never says which) |
| `ERR-FED-00403` | 403 | Authenticated at the provider, but not linked to a user of the organisation |
| `ERR-FED-00404` | 404 | Provider or link not found (another tenant's answers the same) |
| `ERR-FED-00409` | 409 | Illegal lifecycle move, duplicate provider/link, or the provider is not enabled |
| `ERR-FED-00502` | 502 | The identity provider could not be reached or its discovery document is unusable |
| `ERR-FED-00503` | 503 | The client secret named by the provider is not configured on the server |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
