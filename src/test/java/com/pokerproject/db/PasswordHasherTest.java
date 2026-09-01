package com.pokerproject.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void matchingPassphraseVerifiesAgainstItsOwnHash() {
        String hash = PasswordHasher.hash("correct horse battery staple");
        assertTrue(PasswordHasher.matches("correct horse battery staple", hash));
    }

    @Test
    void wrongPassphraseDoesNotVerify() {
        String hash = PasswordHasher.hash("correct horse battery staple");
        assertFalse(PasswordHasher.matches("wrong passphrase", hash));
    }

    @Test
    void hashingTheSamePassphraseTwiceProducesDifferentStoredValues() {
        // The whole reason a database UNIQUE constraint can't enforce passphrase uniqueness -
        // each hash gets its own random salt.
        String first = PasswordHasher.hash("same passphrase");
        String second = PasswordHasher.hash("same passphrase");
        assertNotEquals(first, second);
        assertTrue(PasswordHasher.matches("same passphrase", first));
        assertTrue(PasswordHasher.matches("same passphrase", second));
    }
}
