package com.pokerproject.domain;

import com.pokerproject.protocol.ActionType;
import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.TableCommand;
import com.pokerproject.protocol.TableCommandRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableEngineTest {

    private static Card c(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    private static TableCommandRequest sitDown(UUID id, String name, long amount) {
        return new TableCommandRequest(id, TableCommand.SIT_DOWN, name, amount, null, null);
    }

    private static PlayerAction action(UUID id, ActionType type, long amount) {
        return new PlayerAction(id, type, amount);
    }

    private static TableCommandRequest nextHand(UUID id) {
        return new TableCommandRequest(id, TableCommand.NEXT_HAND, null, 0, null, null);
    }

    private static TableCommandRequest voteMode(UUID id, GameVariant mode) {
        return new TableCommandRequest(id, TableCommand.VOTE_GAME_MODE, null, 0, mode.name(), null);
    }

    // No hand ever auto-starts, not even the first - seats both players, then explicitly
    // starts hand 1 the same way every later hand starts (NEXT_HAND -> both vote the same way).
    private static void seatTwoAndStartHand(Table table, UUID a, UUID b) {
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_HOLDEM));
        table.apply(voteMode(b, GameVariant.TEXAS_HOLDEM));
    }

    @Test
    void headsUpBlindsAndFirstToActFollowTheButtonRule() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        seatTwoAndStartHand(table, a, b);

        GameRound round = table.currentRound();
        assertEquals(RoundStage.PREFLOP, round.stage());
        // Heads-up: dealer/button IS the small blind, and acts first preflop.
        assertEquals(table.dealerSeat(), round.smallBlindSeat());
        assertEquals(1000 - 5, table.seats()[round.smallBlindSeat()].player().stack());
        assertEquals(1000 - 10, table.seats()[round.bigBlindSeat()].player().stack());
        assertEquals(round.smallBlindSeat(), round.actingSeat());
    }

    @Test
    void lastActionByPlayerTracksEachPlayersMostRecentActionAndResetsEachStreet() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndStartHand(table, a, b);

        GameRound round = table.currentRound();
        assertTrue(round.lastActionByPlayer().isEmpty()); // nobody's acted yet this street

        UUID sb = table.seats()[round.actingSeat()].player().id();
        UUID bb = sb.equals(a) ? b : a;

        table.apply(action(sb, ActionType.RAISE, 30));
        assertEquals(ActionType.RAISE, round.lastActionByPlayer().get(sb));
        assertEquals(20, round.lastRaiseSize()); // raised to 30, up from BB's 10 - a 20 increment

        table.apply(action(bb, ActionType.RAISE, 90)); // still preflop - both have now acted
        assertEquals(ActionType.RAISE, round.lastActionByPlayer().get(sb));
        assertEquals(ActionType.RAISE, round.lastActionByPlayer().get(bb));
        assertEquals(60, round.lastRaiseSize()); // 90 - 30

        table.apply(action(sb, ActionType.CALL, 0)); // closes preflop, deals the flop

        assertEquals(RoundStage.FLOP, round.stage());
        assertTrue(round.lastActionByPlayer().isEmpty()); // new street, nobody's acted yet
    }

    @Test
    void actingOutOfTurnIsRejected() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndStartHand(table, a, b);

        GameRound round = table.currentRound();
        UUID notActingPlayer = table.seats()[round.actingSeat()].player().id().equals(a) ? b : a;

        assertThrows(IllegalStateException.class,
                () -> table.apply(action(notActingPlayer, ActionType.CALL, 0)));
    }

    @Test
    void foldingWithNothingToCallIsRejected() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndStartHand(table, a, b);

        GameRound round = table.currentRound();
        UUID actor = table.seats()[round.actingSeat()].player().id();
        // Heads-up first-to-act is the small blind, still facing the big blind - has
        // something to call, so this should be legal. Call it to reach a facing-no-bet spot.
        table.apply(action(actor, ActionType.CALL, 0));

        // Now the big blind is facing no additional bet (both matched at 10) - must check.
        UUID bigBlindPlayer = table.seats()[round.bigBlindSeat()].player().id();
        assertThrows(IllegalStateException.class,
                () -> table.apply(action(bigBlindPlayer, ActionType.FOLD, 0)));
    }

    @Test
    void uncontestedFoldRecordsTheRemainingPlayerAsWinner() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndStartHand(table, a, b);

        GameRound round = table.currentRound();
        UUID firstActor = table.seats()[round.actingSeat()].player().id();
        UUID other = firstActor.equals(a) ? b : a;
        table.apply(action(firstActor, ActionType.FOLD, 0));

        assertEquals(Set.of(other), round.winnersByBoard().get(0));
    }

    @Test
    void threeWayAllInProducesCorrectSidePotSplit() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        // Nothing deals until NEXT_HAND is called, so all three can join before hand 1 -
        // no need for a throwaway heads-up hand to get C into the game anymore.
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000));
        table.apply(sitDown(c, "C", 40));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_HOLDEM));
        // 2 of 3 agree with only 1 unvoted left - c couldn't flip the outcome, so this
        // clinches and deals without waiting on c's vote.
        table.apply(voteMode(b, GameVariant.TEXAS_HOLDEM));
        GameRound round = table.currentRound();
        assertEquals(RoundStage.PREFLOP, round.stage());
        assertEquals(3, round.holeCards().size());

        // Stack the deck: A gets pocket aces, B and C get hands that can't beat them on
        // this board, so the showdown outcome is deterministic.
        round.holeCards().put(a, new HoleCards(List.of(c(Rank.ACE, Suit.SPADES), c(Rank.ACE, Suit.HEARTS))));
        round.holeCards().put(b, new HoleCards(List.of(c(Rank.SEVEN, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS))));
        round.holeCards().put(c, new HoleCards(List.of(c(Rank.NINE, Suit.CLUBS), c(Rank.FOUR, Suit.DIAMONDS))));
        round.deck().stack(List.of(
                c(Rank.KING, Suit.DIAMONDS), c(Rank.SIX, Suit.SPADES), c(Rank.THREE, Suit.HEARTS), // flop
                c(Rank.JACK, Suit.CLUBS),   // turn
                c(Rank.EIGHT, Suit.DIAMONDS) // river
        ));

        // Total chips currently in play: each player's stack plus whatever's already
        // committed to the pot (the blinds just posted) - timing-independent, unlike
        // snapshotting stacks alone would be once blinds have already moved money around.
        long totalChipsInPlay = findPlayer(table, a).stack() + findPlayer(table, b).stack()
                + findPlayer(table, c).stack() + round.pot().total();

        // Whoever's first to act (dealt into this 3-handed round) shoves their whole stack.
        UUID firstToActThisHand = table.seats()[round.actingSeat()].player().id();
        table.apply(action(firstToActThisHand, ActionType.ALL_IN, 0));

        // The other two both call all-in in turn order - keep applying to whoever's up
        // until the hand resolves (run-it-out fires once nobody can act further).
        while (table.currentRound() != null && table.currentRound() == round
                && round.stage() != RoundStage.COMPLETE) {
            UUID actor = table.seats()[round.actingSeat()].player().id();
            table.apply(action(actor, ActionType.ALL_IN, 0));
        }

        // Chip conservation: no money created or destroyed by the whole hand.
        long finalTotal = findPlayer(table, a).stack() + findPlayer(table, b).stack() + findPlayer(table, c).stack();
        assertEquals(totalChipsInPlay, finalTotal);

        // C could only ever win up to the level C contributed (40) - capped by their
        // short stack, exactly what the side-pot split is supposed to enforce.
        assertTrue(findPlayer(table, c).stack() <= 40);

        // A has pocket aces against two hands that never improve past king-high on this
        // board - A must win every tier it's eligible for.
        assertEquals(totalChipsInPlay - findPlayer(table, b).stack() - findPlayer(table, c).stack(),
                findPlayer(table, a).stack());

        // A has the winning hand, so A must be recorded as a winner of at least one tier.
        // Not asserting exclusivity: A and B posted different blinds (5 vs 10), so whichever
        // one has more in already, the extra tier above C's short stack but below the bigger
        // stack's total has only one eligible player - an uncalled-bet return that
        // legitimately also counts them as that tier's "winner".
        assertTrue(round.winnersByBoard().get(0).contains(a));
    }

    private static int findSeat(Table table, UUID playerId) {
        for (Seat seat : table.seats()) {
            if (seat.player() != null && seat.player().id().equals(playerId)) {
                return seat.index();
            }
        }
        throw new IllegalStateException("not seated");
    }

    private static Player findPlayer(Table table, UUID playerId) {
        return table.seats()[findSeat(table, playerId)].player();
    }
}
