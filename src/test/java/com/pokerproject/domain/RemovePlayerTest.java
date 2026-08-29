package com.pokerproject.domain;

import com.pokerproject.protocol.ActionType;
import com.pokerproject.protocol.GameVariant;
import com.pokerproject.protocol.PlayerAction;
import com.pokerproject.protocol.TableCommand;
import com.pokerproject.protocol.TableCommandRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

// PokerServer verifies a REMOVE_PLAYER target has no live connection before this ever reaches
// Table - Table has no notion of a connection at all, so these tests only cover the domain-level
// removal and forced-fold bookkeeping. The connection-liveness guard itself is proven over the
// real wire in PokerServerIntegrationTest.
class RemovePlayerTest {

    private static TableCommandRequest sitDown(UUID id, String name, long amount) {
        return new TableCommandRequest(id, TableCommand.SIT_DOWN, name, amount, null, null);
    }

    private static TableCommandRequest remove(UUID requester, UUID target) {
        return new TableCommandRequest(requester, TableCommand.REMOVE_PLAYER, null, 0, null, target.toString());
    }

    private static TableCommandRequest nextHand(UUID id) {
        return new TableCommandRequest(id, TableCommand.NEXT_HAND, null, 0, null, null);
    }

    private static TableCommandRequest voteMode(UUID id, GameVariant mode) {
        return new TableCommandRequest(id, TableCommand.VOTE_GAME_MODE, null, 0, mode.name(), null);
    }

    private static PlayerAction action(UUID id, ActionType type, long amount) {
        return new PlayerAction(id, type, amount);
    }

    private static boolean stillSeated(Table table, UUID playerId) {
        for (Seat seat : table.seats()) {
            if (seat.player() != null && seat.player().id().equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void removingAnUnseatedTargetIsRejected() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));

        assertThrows(IllegalStateException.class, () -> table.apply(remove(a, UUID.randomUUID())));
    }

    @Test
    void theRequesterMustBeSeated() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));

        assertThrows(IllegalStateException.class, () -> table.apply(remove(UUID.randomUUID(), a)));
    }

    @Test
    void removingAPlayerBetweenHandsJustFreesTheSeat() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000)); // hand 1 starts, heads-up

        GameRound round = table.currentRound();
        UUID firstActor = table.seats()[round.actingSeat()].player().id();
        table.apply(action(firstActor, ActionType.FOLD, 0)); // COMPLETE - not mid-hand anymore

        UUID other = firstActor.equals(a) ? b : a;
        table.apply(remove(other, firstActor));

        assertFalse(stillSeated(table, firstActor));
    }

    @Test
    void removingTheCurrentlyActingPlayerResolvesTheHandLikeAFold() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000)); // heads-up hand starts

        GameRound round = table.currentRound();
        UUID actingPlayer = table.seats()[round.actingSeat()].player().id();
        UUID other = actingPlayer.equals(a) ? b : a;

        table.apply(remove(other, actingPlayer));

        // Heads-up, and the only other player left in the hand wins it uncontested - same
        // outcome a real FOLD from the acting player would have produced.
        assertEquals(RoundStage.COMPLETE, table.currentRound().stage());
        assertFalse(stillSeated(table, actingPlayer));
    }

    @Test
    void removingANonActingPlayerMidHandDoesNotDisturbTheCurrentTurn() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 0));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000)); // hand 1 starts, heads-up

        GameRound hand1 = table.currentRound();
        UUID firstActor = table.seats()[hand1.actingSeat()].player().id();
        table.apply(action(firstActor, ActionType.FOLD, 0)); // COMPLETE

        table.apply(sitDown(c, "C", 1000)); // now 3 occupied, between hands
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_HOLDEM)); // deals hand 2, 3-handed

        GameRound hand2 = table.currentRound();
        assertEquals(3, hand2.holeCards().size());
        UUID actingPlayer = table.seats()[hand2.actingSeat()].player().id();
        UUID bystander = Stream.of(a, b, c).filter(id -> !id.equals(actingPlayer)).findFirst().orElseThrow();
        int actingSeatBefore = hand2.actingSeat();

        table.apply(remove(actingPlayer, bystander));

        assertEquals(actingSeatBefore, table.currentRound().actingSeat());
        assertFalse(stillSeated(table, bystander));
        assertEquals(RoundStage.PREFLOP, table.currentRound().stage()); // still 2 live, hand continues
    }
}
