package com.pokerproject.db;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

// PBKDF2WithHmacSHA256 via javax.crypto - already in the JDK, zero new dependency for the same
// job bcrypt/argon2 would do (slow, salted, one-way) at a threat model with no realistic
// brute-force exposure. Stored as "iterations:base64(salt):base64(hash)" so the iteration count
// can be bumped later without invalidating hashes already on disk.
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String passphrase) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(passphrase, salt, ITERATIONS);
        return ITERATIONS + ":" + encode(salt) + ":" + encode(hash);
    }

    public static boolean matches(String passphrase, String stored) {
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed stored hash");
        }
        int iterations = Integer.parseInt(parts[0]);
        byte[] salt = decode(parts[1]);
        byte[] expected = decode(parts[2]);
        byte[] actual = pbkdf2(passphrase, salt, iterations);
        return MessageDigest.isEqual(expected, actual); // constant-time - no timing side channel
    }

    private static byte[] pbkdf2(String passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String s) {
        return Base64.getDecoder().decode(s);
    }
}
