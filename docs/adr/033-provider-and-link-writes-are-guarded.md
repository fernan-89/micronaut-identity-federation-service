# ADR 033: Provider and link writes are guarded writes

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
A provider or link change read the document, validated it in the domain and wrote it by `_id` only. Measured by `TransitionRaceIT` on the old code: ten revocations of one federated link at once gave five OK answers. (A provider change decides how people sign in, so a lost update there matters more than its size suggests.)

## Decision
`IdentityProvider.updateStatus`, `IdentityProvider.updateSettings` and `FederatedLink.updateStatus` are **guarded writes** on the status the caller read (the `fromStatus` of the audit entry they carry, so no use case changed): no match is `409 ERR-FED-00409` when the document exists and `404` when it does not (ADR-019).

## Consequences
- **Positive:** one winner per race; the stored status and the trail agree.
