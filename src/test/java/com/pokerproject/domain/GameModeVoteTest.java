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

// GameVariant now has two values (TEXAS_HOLDEM, OMAHA), so a single vote no longer clinches
// on its own - these tests have every seated player vote the same way to reach a decided,
// dealt hand. The leader-vs-rival math itself (clinching early once a rival can't catch up,
// ties broken at random once everyone's voted) isn't separately covered here yet.
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

    // Seats A and B, explicitly starts hand 1 (no hand ever auto-starts, not even the first),
    // and folds it uncontested, landing on COMPLETE without ever touching voting again - the
    // state every test in this file actually cares about starts from.
    private static void seatTwoAndFinishHandOne(Table table, UUID a, UUID b) {
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000));
        table.apply(nextHand(a));
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));
        table.apply(vote(b, GameVariant.TEXAS_HOLDEM));
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
        table.apply(sitDown(b, "B", 1000));
        table.apply(nextHand(a));
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));
        table.apply(vote(b, GameVariant.TEXAS_HOLDEM)); // hand 1 starts, live

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
        table.apply(sitDown(b, "B", 1000));
        table.apply(nextHand(a));
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));
        table.apply(vote(b, GameVariant.TEXAS_HOLDEM)); // hand 1 starts, live - voting not open again yet

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

        // Once every seated player has voted the same way, no rival could ever catch up -
        // checkVoteOutcome decides and deals in the same call as the last vote.
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));
        table.apply(vote(b, GameVariant.TEXAS_HOLDEM));

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
        table.apply(vote(a, GameVariant.TEXAS_HOLDEM));
        table.apply(vote(b, GameVariant.TEXAS_HOLDEM)); // both voted the same way - decides and deals hand 2

        assertThrows(IllegalStateException.class, () -> table.apply(vote(a, GameVariant.TEXAS_HOLDEM)));
    }

    @Test
    void votingForAnUnknownModeIsRejectedEvenWhileOpen() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));

        assertThrows(IllegalStateException.class,
                () -> table.apply(new TableCommandRequest(a, TableCommand.VOTE_GAME_MODE, null, 0, "SEVEN_CARD_STUD", null)));
    }
}
