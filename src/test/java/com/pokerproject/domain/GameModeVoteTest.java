package com.pokerproject.domain;

import com.pokerproject.protocol.ActionType;
import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.TableCommand;
import com.pokerproject.protocol.TableCommandRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// GameVariant has only one value today, so the multi-candidate side of checkVoteOutcome's math
// (a leader beating the best-positioned rival, ties broken at random when everyone's voted)
// isn't reachable through the public API yet - these tests cover everything that IS reachable:
// the "no rival could ever exist" instant-decide path, voting only being open in the gap
// between hands, and the new hand dealing the instant a mode is decided. Re-verify the N>1
// math by hand once a second variant with real rules exists.
class GameModeVoteTest {

    private static TableCommandRequest sitDown(UUID id, String name, long amount) {
        return new TableCommandRequest(id, TableCommand.SIT_DOWN, name, amount, null, null);
    }

    private static TableCommandRequest vote(UUID id, GameVariant mode) {
        return new TableCommandRequest(id, TableCommand.VOTE_GAME_MODE, null, 0, mode.name(), null);
    }

    private static TableCommandRequest nextHand(UUID id) {
        return new TableCommandRequest(id, TableCommand.NEXT_HAND, null, 0, null, null);
    }

    private static PlayerAction action(UUID id, ActionType type, long amount) {
        return new PlayerAction(id, type, amount);
    }

    // Seats A and B (auto-starts hand 1) and folds it uncontested, landing on COMPLETE without
    // ever touching voting - the state every test in this file actually cares about starts from.
    private static void seatTwoAndFinishHandOne(Table table, UUID a, UUID b) {
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000));
        GameRound round = table.currentRound();
        UUID firstActor = table.seats()[round.actingSeat()].player().id();
        table.apply(action(firstActor, ActionType.FOLD, 0));
    }

    @Test
    void votingRequiresASeatedPlayer() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        assertThrows(IllegalStateException.class,
                () -> table.apply(vote(UUID.randomUUID(), GameVariant.TEXAS_HOLDEM)));
    }

    @Test
    void nextHandIsRejectedWhileAHandIsInProgress() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000)); // hand 1 auto-starts, live

        assertThrows(IllegalStateException.class, () -> table.apply(nextHand(a)));
    }

    @Test
    void nextHandRequiresTwoPlayers() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000)); // alone - no hand starts

        assertThrows(IllegalStateException.class, () -> table.apply(nextHand(a)));
    }

    @Test
    void votingIsRejectedUntilNextHandOpensIt() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000)); // hand 1 auto-starts, live - voting not open yet

        assertThrows(IllegalStateException.class, () -> table.apply(vote(a, GameVariant.TEXAS_HOLDEM)));
    }

    @Test
    void nextHandOpensVotingWithoutDisturbingTheShowdownResult() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        assertFalse(table.votingOpen());
        assertEquals(RoundStage.COMPLETE, table.currentRound().stage());

        table.apply(nextHand(a));

        assertTrue(table.votingOpen());
        // Still showing hand 1's result - nothing dealt until a mode is decided.
        assertEquals(RoundStage.COMPLETE, table.currentRound().stage());
    }

    @Test
    void voteImmediatelyDealsTheNextHandOnceDecided() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));

        // With only one GameVariant value in existence there's no rival that could ever catch
        // up - checkVoteOutcome's "rivals.isEmpty()" branch decides and deals on the first vote.
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));

        assertFalse(table.votingOpen());
        assertTrue(table.voteTally().isEmpty()); // reset for the window after this hand
        assertEquals(GameVariant.TEXAS_HOLDEM, table.variant());
        assertEquals(RoundStage.PREFLOP, table.currentRound().stage());
    }

    @Test
    void voteAfterTheHandHasDealtIsRejected() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM)); // decides and deals hand 2

        assertThrows(IllegalStateException.class, () -> table.apply(vote(b, GameVariant.TEXAS_HOLDEM)));
    }

    @Test
    void votingForAnUnknownModeIsRejectedEvenWhileOpen() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));

        assertThrows(IllegalStateException.class,
                () -> table.apply(new TableCommandRequest(a, TableCommand.VOTE_GAME_MODE, null, 0, "OMAHA", null)));
    }
}
