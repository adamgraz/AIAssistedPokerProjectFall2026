package com.pokerproject.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Live-session crash recovery, not lifetime stats (that's Profile). No instance state - unlike
// Profile, nothing holds one of these across calls, so this is a static DAO over table_state
// rather than an Active Record.
public final class TableState {

    private TableState() {
    }

    public record Row(UUID playerUuid, String displayName, long stack, long totalBuyIn) {
    }

    // Snapshot, not delta - overwrites the whole row. Written twice, or retried after a crash
    // mid-write, lands on the same value either way; an incrementing write wouldn't.
    public static void snapshot(UUID playerId, String displayName, long stack, long totalBuyIn) {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO table_state (PlayerUuid, DisplayName, Stack, TotalBuyIn, UpdatedAt) "
                             + "VALUES (?, ?, ?, ?, current_timestamp) "
                             + "ON CONFLICT (PlayerUuid) DO UPDATE SET "
                             + "DisplayName = excluded.DisplayName, Stack = excluded.Stack, "
                             + "TotalBuyIn = excluded.TotalBuyIn, UpdatedAt = excluded.UpdatedAt")) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, displayName);
            stmt.setLong(3, stack);
            stmt.setLong(4, totalBuyIn);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Row> all() {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM table_state")) {
            ResultSet results = stmt.executeQuery();
            List<Row> rows = new ArrayList<>();
            while (results.next()) {
                rows.add(new Row(
                        UUID.fromString(results.getString("PlayerUuid")),
                        results.getString("DisplayName"),
                        results.getLong("Stack"),
                        results.getLong("TotalBuyIn")));
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Fired once a player's result is permanently recorded (Profile.recordNetChips) - the
    // scratch copy has to go, or a re-seated guest UUID could inherit someone else's stale row.
    public static void deleteFor(UUID playerId) {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM table_state WHERE PlayerUuid = ?")) {
            stmt.setString(1, playerId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Fired when the table is explicitly closed (END_TABLE) - the protocol's own "night's
    // over" signal, and the clean way this snapshot goes away.
    public static void clearAll() {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM table_state")) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Run once at startup, before all() - without this, a server restarted a week after a
    // crash would silently reseat everyone at whatever stacks that old crash left them at.
    // After pruning, everything left in the table is fresh by construction, so all() needs
    // no separate time filter of its own.
    public static void pruneStale() {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM table_state WHERE UpdatedAt < datetime('now', '-2 hours')")) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
