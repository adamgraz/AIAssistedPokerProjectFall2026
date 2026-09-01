package com.pokerproject.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {

    @BeforeAll
    static void useTestDatabase() {
        DB.useTestMode();
    }

    @BeforeEach
    void freshSchema() {
        DB.reset(); // no seed data to restore (unlike Chinook) - just an empty schema each time
    }

    private static Profile createProfile(String displayName, String passphrase) {
        Profile profile = new Profile(displayName, PasswordHasher.hash(passphrase));
        profile.create();
        return profile;
    }

    @Test
    void createAssignsAProfileIdAndAPlayerUuid() {
        Profile profile = createProfile("Alice", "hunter2");
        assertNotNull(profile.getProfileId());
        assertNotNull(profile.getPlayerUuid());
    }

    @Test
    void findByPassphraseReturnsTheMatchingProfile() {
        Profile created = createProfile("Alice", "hunter2");
        Profile found = Profile.findByPassphrase("hunter2");
        assertNotNull(found);
        assertEquals(created.getPlayerUuid(), found.getPlayerUuid());
        assertEquals("Alice", found.getDisplayName());
    }

    @Test
    void findByPassphraseReturnsNullForAnUnknownPassphrase() {
        createProfile("Alice", "hunter2");
        assertNull(Profile.findByPassphrase("not the passphrase"));
    }

    @Test
    void findByUuidReturnsEmptyForAGuestUuidWithNoBackingRow() {
        // Exactly the guest case - a UUID that was never inserted anywhere.
        Optional<Profile> found = Profile.findByUuid(UUID.randomUUID());
        assertTrue(found.isEmpty());
    }

    @Test
    void findByUuidReturnsTheProfileOnceCreated() {
        Profile created = createProfile("Bob", "correct horse battery staple");
        Optional<Profile> found = Profile.findByUuid(created.getPlayerUuid());
        assertTrue(found.isPresent());
        assertEquals("Bob", found.get().getDisplayName());
    }

    @Test
    void recordHandPlayedIncrementsTheLifetimeCount() {
        Profile profile = createProfile("Alice", "hunter2");
        profile.recordHandPlayed();
        profile.recordHandPlayed();
        assertEquals(2, profile.getHandsPlayed());

        Profile reloaded = Profile.findByUuid(profile.getPlayerUuid()).orElseThrow();
        assertEquals(2, reloaded.getHandsPlayed());
    }

    @Test
    void recordNetChipsAccumulatesAcrossMoreThanOneSession() {
        Profile profile = createProfile("Alice", "hunter2");
        profile.recordNetChips(150); // won 150 in an earlier session, already left
        profile.recordNetChips(-40); // lost 40 in a later session

        assertEquals(110, profile.getNetChips());
        Profile reloaded = Profile.findByUuid(profile.getPlayerUuid()).orElseThrow();
        assertEquals(110, reloaded.getNetChips());
    }
}
