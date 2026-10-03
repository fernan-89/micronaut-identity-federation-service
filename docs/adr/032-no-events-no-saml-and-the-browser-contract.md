# ADR-032: No Events, No SAML, and the Browser Contract

## Status
Accepted

## Decision
- **No events.** Nothing publishes or consumes a federation event today; the sign-in is synchronous and the ledger already records the
  mutating calls the gateway sees. Events can be added when a service needs to react to a link change.
- **No SAML and no SCIM** (ADR-030).
- **The browser contract.** The person's browser only ever talks to the platform gateway, under the web app's `/api` prefix:
  1. `GET /identity-federation/v1/login/initiate?organisationId=` answers `302` to the provider's authorization URL.
  2. The provider sends the browser back to `GET /identity-federation/v1/login/callback?code=&state=` (the `redirect-uri` setting is that
     URL as the browser sees it, and is what an administrator registers at the provider).
  3. This service answers the session as JSON, like a password login. **The gateway** (gateway ADR-025) intercepts that answer: it moves
     the refresh token into an HttpOnly, Secure, SameSite=Strict cookie and redirects the browser to the web app, so the refresh token
     never reaches the page's scripts. The web app then obtains its access token with `POST /gateway/v1/session/refresh`.
  Both login routes are public (the person has no token yet); everything else needs a token with the administrator role.
- A provider-side refusal (`error=access_denied`...) is `401` and nothing the provider wrote is echoed back.

## Consequences
- Positive: a small, reviewable surface; a stolen page script cannot steal a refresh token.
- Negative: the callback URL must be registered at the provider and must match the gateway's public address; deploying behind another
  host means changing `THINKLAB_FEDERATION_REDIRECT_URI`.
