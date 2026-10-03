package com.thinklab.domain.model;

/**
 * What the platform keeps of a validated ID token, for the length of one sign-in and no longer: the provider's identifier for the
 * person, and the email and name only so a first-time user can be provisioned when the organisation allows it.
 *
 * @param emailVerified whether the provider says it verified the email; an unverified email never provisions anyone
 */
public record IdentityClaims(String subject, String email, boolean emailVerified, String name) {}
