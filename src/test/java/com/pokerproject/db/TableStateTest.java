package com.pokerproject.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableStateTest {

    @BeforeAll
    static void useTestDatabase() {
        DB.useTestMode();
    }

    @BeforeEach
    void freshSchema() {
        DB.reset();
    }

    @Test
    void snapshotThenAllReturnsWhatWasWritten() {
        UUID id = UUID.randomUUID();
        TableState.snapshot(id, "Alice", 750, 1000);

        List<TableState.Row> rows = TableState.all();
        assertEquals(1, rows.size());
        assertEquals(id, rows.get(0).playerUuid());
        assertEquals("Alice", rows.get(0).displayName());
        assertEquals(750, rows.get(0).stack());
        assertEquals(1000, rows.get(0).totalBuyIn());
    }

    // The whole point of a snapshot over a delta: writing it twice for the same player
    // overwrites in place rather than accumulating a second row or a summed value.
    @Test
    void snapshotOverwritesRatherThanAccumulating() {
        UUID id = UUID.randomUUID();
        TableState.snapshot(id, "Alice", 1000, 1000);
        TableState.snapshot(id, "Alice", 1450, 1000); // won a pot since the last snapshot

        List<TableState.Row> rows = TableState.all();
        assertEquals(1, rows.size());
        assertEquals(1450, rows.get(0).stack());
    }

    // Distinguishes a rebuy from a pot win: stack and totalBuyIn move together on rebuy, so
    // reconstructed profit (stack - totalBuyIn) is unchanged by the rebuy itself, even though
    // the stack number went up. A pot win would move stack alone and change this.
    @Test
    void stackAndTotalBuyInMoveTogetherOnARebuySnapshot() {
        UUID id = UUID.randomUUID();
        TableState.snapshot(id, "Alice", 0, 500); // busted, down 500
        long profitBeforeRebuy = TableState.all().get(0).stack() - TableState.all().get(0).totalBuyIn();

        TableState.snapshot(id, "Alice", 500, 1000); // rebought 500

        TableState.Row row = TableState.all().get(0);
        long profitAfterRebuy = row.stack() - row.totalBuyIn();
        assertEquals(profitBeforeRebuy, profitAfterRebuy);
        assertEquals(-500, profitAfterRebuy);
    }

    @Test
    void deleteForRemovesOnlyThatPlayersRow() {
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        TableState.snapshot(leaving, "Alice", 500, 500);
        TableState.snapshot(staying, "Bob", 500, 500);

        TableState.deleteFor(leaving);

        List<TableState.Row> rows = TableState.all();
        assertEquals(1, rows.size());
        assertEquals(staying, rows.get(0).playerUuid());
    }

    @Test
    void clearAllWipesEveryRow() {
        TableState.snapshot(UUID.randomUUID(), "Alice", 500, 500);
        TableState.snapshot(UUID.randomUUID(), "Bob", 500, 500);

        TableState.clearAll();

        assertTrue(TableState.all().isEmpty());
    }

    // Proves the staleness gate without waiting two real hours - back-date the row directly,
    // same trick the doc calls for.
    @Test
    void pruneStaleRemovesOnlyRowsOlderThanTwoHours() throws Exception {
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        TableState.snapshot(stale, "Alice", 500, 500);
        TableState.snapshot(fresh, "Bob", 500, 500);
        backdate(stale, "-3 hours");
        backdate(fresh, "-1 hours");

        TableState.pruneStale();

        List<TableState.Row> rows = TableState.all();
        assertEquals(1, rows.size());
        assertEquals(fresh, rows.get(0).playerUuid());
    }

    private static void backdate(UUID playerId, String sqliteOffset) throws Exception {
        try (Connection conn = DB.connect();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE table_state SET UpdatedAt = datetime('now', ?) WHERE PlayerUuid = ?")) {
            stmt.setString(1, sqliteOffset);
            stmt.setString(2, playerId.toString());
            stmt.executeUpdate();
        }
    }
}
