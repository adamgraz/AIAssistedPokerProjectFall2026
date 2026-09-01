package com.pokerproject.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// CSCI 440's "manual" package pattern: the entity owns its own SQL (create/find/record*)
// instead of a separate repository class. Public constructor for a brand-new profile, a
// separate private ResultSet constructor for hydrating one from a query row - same split
// Artist.java uses, for the same reason (a fresh profile has no ProfileId/PlayerUuid yet).
public final class Profile {

    private Long profileId;
    private UUID playerUuid;
    private final String displayName;
    private final String passphraseHash;
    private long handsPlayed;
    private long netChips;

    public Profile(String displayName, String passphraseHash) {
        this.displayName = displayName;
        this.passphraseHash = passphraseHash;
    }

    private Profile(ResultSet results) throws SQLException {
        profileId = results.getLong("ProfileId");
        playerUuid = UUID.fromString(results.getString("PlayerUuid"));
        displayName = results.getString("DisplayName");
        passphraseHash = results.getString("PassphraseHash");
        handsPlayed = results.getLong("HandsPlayed");
        netChips = results.getLong("NetChips");
    }

    public Long getProfileId() {
        return profileId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getHandsPlayed() {
        return handsPlayed;
    }

    public long getNetChips() {
        return netChips;
    }

    // Inserts this profile and assigns its ProfileId (from last_insert_rowid, CSCI 440-style)
    // and PlayerUuid (generated in Java, not by SQLite - Table already expects a UUID as playerId
    // everywhere, so the row's real integer primary key never needs to leave this class).
    public boolean create() {
        playerUuid = UUID.randomUUID();
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO profiles (PlayerUuid, DisplayName, PassphraseHash) VALUES (?, ?, ?)")) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, displayName);
            stmt.setString(3, passphraseHash);
            stmt.executeUpdate();
            profileId = DB.getLastId(conn);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Passphrase-only login (no separate username field) - checks the entered passphrase
    // against every stored hash and returns null on no match, same null-on-not-found
    // convention as Artist.find. Fine at friends-game scale; a salted hash can't be looked
    // up by index regardless of how many rows there are.
    public static Profile findByPassphrase(String passphrase) {
        for (Profile candidate : all()) {
            if (PasswordHasher.matches(passphrase, candidate.passphraseHash)) {
                return candidate;
            }
        }
        return null;
    }

    // Optional here deliberately, unlike findByPassphrase above: every seated player's UUID
    // reaches this lookup, guest or not, and a guest has no row to find. Optional forces the
    // caller (the seat-vacated and hand-complete hooks) to explicitly handle "no profile" as a
    // real branch instead of a forgotten null-check.
    public static Optional<Profile> findByUuid(UUID playerUuid) {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM profiles WHERE PlayerUuid = ?")) {
            stmt.setString(1, playerUuid.toString());
            ResultSet results = stmt.executeQuery();
            if (results.next()) {
                return Optional.of(new Profile(results));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Profile> all() {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM profiles")) {
            ResultSet results = stmt.executeQuery();
            List<Profile> profiles = new ArrayList<>();
            while (results.next()) {
                profiles.add(new Profile(results));
            }
            return profiles;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Bumped once per completed hand, for every player dealt into it - folded or not, showdown
    // or not (see Table.onHandComplete, which fires identically either way).
    public void recordHandPlayed() {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE profiles SET HandsPlayed = HandsPlayed + 1 WHERE ProfileId = ?")) {
            stmt.setLong(1, profileId);
            stmt.executeUpdate();
            handsPlayed++;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Called once per profile, at the moment their seat is actually vacated - not per hand, so
    // mid-session rebuys never get double-counted as "winnings" (see Table.setOnPlayerLeftSeat).
    public void recordNetChips(long delta) {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE profiles SET NetChips = NetChips + ? WHERE ProfileId = ?")) {
            stmt.setLong(1, delta);
            stmt.setLong(2, profileId);
            stmt.executeUpdate();
            netChips += delta;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
