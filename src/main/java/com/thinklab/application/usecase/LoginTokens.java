package com.thinklab.application.usecase;

import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The random values of a sign-in: {@code state}, {@code nonce} and the PKCE {@code code_verifier} are each 256 random bits,
 * URL-safe base64 (43 characters, the RFC 7636 verifier alphabet), and the challenge is the S256 transform of the verifier.
 */
@Singleton
public class LoginTokens {

    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@code BASE64URL(SHA256(verifier))}, RFC 7636 section 4.2. */
    public String challengeFor(String verifier) {
        return challengeFor(verifier, "SHA-256");
    }

    String challengeFor(String verifier, String algorithm) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm is not available in this JVM: " + algorithm, e);
        }
    }
}
