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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombPotTest {

    private static Card c(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    private static TableCommandRequest sitDown(UUID id, String name, long amount) {
        return new TableCommandRequest(id, TableCommand.SIT_DOWN, name, amount, null, null);
    }

    private static TableCommandRequest nextHand(UUID id) {
        return new TableCommandRequest(id, TableCommand.NEXT_HAND, null, 0, null, null);
    }

    private static TableCommandRequest voteMode(UUID id, GameVariant mode) {
        return new TableCommandRequest(id, TableCommand.VOTE_GAME_MODE, null, 0, mode.name(), null);
    }

    private static TableCommandRequest bombPotOpt(UUID id, boolean optIn) {
        return new TableCommandRequest(id, TableCommand.BOMB_POT_OPT, null, optIn ? 1 : 0, null, null);
    }

    private static PlayerAction action(UUID id, ActionType type, long amount) {
        return new PlayerAction(id, type, amount);
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

    // Seats A and B, explicitly starts hand 1 heads-up (no hand ever auto-starts, not even
    // the first), and folds it uncontested, landing on COMPLETE - the state every test in
    // this file starts from, same pattern as GameModeVoteTest.
    private static void seatTwoAndFinishHandOne(Table table, UUID a, UUID b) {
        table.apply(sitDown(a, "A", 1000));
        table.apply(sitDown(b, "B", 1000));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_HOLDEM));
        table.apply(voteMode(b, GameVariant.TEXAS_HOLDEM));
        GameRound round = table.currentRound();
        UUID firstActor = table.seats()[round.actingSeat()].player().id();
        table.apply(action(firstActor, ActionType.FOLD, 0));
    }

    @Test
    void decidingABombPotOpensOptInInsteadOfDealingImmediately() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));

        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT)); // both agree - decides

        assertFalse(table.votingOpen());
        assertTrue(table.bombPotOptInOpen());
        assertEquals(GameVariant.TEXAS_BOMB_POT, table.pendingBombPotVariant());
        // Still showing hand 1's result - nothing dealt until opt-in resolves.
        assertEquals(RoundStage.COMPLETE, table.currentRound().stage());
    }

    @Test
    void nextHandIsRejectedWhileBombPotOptInIsOpen() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));

        assertThrows(IllegalStateException.class, () -> table.apply(nextHand(a)));
    }

    @Test
    void optInIsRejectedWhileTheWindowIsntOpen() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);

        assertThrows(IllegalStateException.class, () -> table.apply(bombPotOpt(a, true)));
    }

    @Test
    void windowStaysOpenUntilEveryEligiblePlayerResponds() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));

        table.apply(bombPotOpt(a, true));
        assertTrue(table.bombPotOptInOpen()); // still waiting on B

        table.apply(bombPotOpt(b, true));
        assertFalse(table.bombPotOptInOpen()); // both answered - deals
        assertEquals(RoundStage.FLOP, table.currentRound().stage());
    }

    @Test
    void fewerThanTwoOptInsFallsBackToVotingInsteadOfDealing() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(sitDown(cId, "C", 1000));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));

        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, false));
        table.apply(bombPotOpt(cId, false)); // only 1 opted in - not enough to play

        assertFalse(table.bombPotOptInOpen());
        assertTrue(table.votingOpen()); // back to voting, no NEXT_HAND needed
        assertEquals(RoundStage.COMPLETE, table.currentRound().stage()); // nothing dealt
    }

    @Test
    void expireDefaultsUnansweredPlayersToOptedOut() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(sitDown(cId, "C", 1000));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));

        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, true));
        // C never responds - simulates the wire layer's 60s timeout firing.
        table.expireBombPotOptInWindow();

        assertFalse(table.bombPotOptInOpen());
        assertEquals(RoundStage.FLOP, table.currentRound().stage()); // A and B were enough
        assertEquals(2, table.currentRound().holeCards().size());
        assertFalse(table.currentRound().holeCards().containsKey(cId));
    }

    @Test
    void expireIsANoOpOnceTheWindowAlreadyResolved() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));
        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, true)); // resolves and deals

        GameRound dealtRound = table.currentRound();
        table.expireBombPotOptInWindow(); // stale timer firing late - must be harmless

        assertEquals(dealtRound, table.currentRound());
        assertEquals(RoundStage.FLOP, table.currentRound().stage());
    }

    @Test
    void bombPotDealsOnlyOptedInPlayersWithAnteInsteadOfBlinds() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(sitDown(cId, "C", 1000));
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));

        long bStackBeforeOptOut = findPlayer(table, b).stack();
        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, false));
        table.apply(bombPotOpt(cId, true));

        GameRound round = table.currentRound();
        assertEquals(RoundStage.FLOP, round.stage());
        assertEquals(Set.of(a, cId), round.holeCards().keySet());
        assertEquals(-1, round.smallBlindSeat());
        assertEquals(-1, round.bigBlindSeat());
        assertEquals(bStackBeforeOptOut, findPlayer(table, b).stack()); // B posted nothing
        assertEquals(2, round.boards().size());
        assertEquals(3, round.boards().get(0).size());
        assertEquals(3, round.boards().get(1).size());
        // First to act is the first active-and-dealt-in seat left of the button - B (opted
        // out, not dealt in) must be skipped even though B is still an ACTIVE-status player.
        assertEquals(findSeat(table, cId), round.actingSeat());
    }

    @Test
    void eachBoardIsWonAndPaidIndependently() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.TEXAS_BOMB_POT));
        table.apply(voteMode(b, GameVariant.TEXAS_BOMB_POT));
        // Hand 1's blinds already left A and B unequal (whoever folded is down 5) - capture
        // each of their own pre-ante stacks rather than assuming a symmetric 1000/1000.
        long aStackBefore = findPlayer(table, a).stack();
        long bStackBefore = findPlayer(table, b).stack();
        long startingTotal = aStackBefore + bStackBefore;

        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, true));

        GameRound round = table.currentRound();
        // A gets quad kings on board A (unbeatable there); B gets quad nines on board B
        // (unbeatable there) - a deliberately split result so the test proves both boards
        // are evaluated and paid independently, not just "someone wins everything".
        round.holeCards().put(a, new HoleCards(List.of(c(Rank.KING, Suit.DIAMONDS), c(Rank.KING, Suit.CLUBS))));
        round.holeCards().put(b, new HoleCards(List.of(c(Rank.NINE, Suit.CLUBS), c(Rank.NINE, Suit.DIAMONDS))));
        round.boards().get(0).clear();
        round.boards().get(0).addAll(List.of(c(Rank.KING, Suit.SPADES), c(Rank.KING, Suit.HEARTS), c(Rank.TWO, Suit.CLUBS)));
        round.boards().get(1).clear();
        round.boards().get(1).addAll(List.of(c(Rank.NINE, Suit.SPADES), c(Rank.NINE, Suit.HEARTS), c(Rank.FIVE, Suit.CLUBS)));
        round.deck().stack(List.of(
                c(Rank.THREE, Suit.DIAMONDS), c(Rank.SIX, Suit.DIAMONDS),   // turn: board A, board B
                c(Rank.FOUR, Suit.HEARTS), c(Rank.SEVEN, Suit.HEARTS)));    // river: board A, board B

        // Both players check every street - no betting beyond the ante, so the pot is exactly
        // the two antes and there's nothing to disentangle from a real betting sequence.
        while (round.stage() != RoundStage.COMPLETE) {
            UUID actor = table.seats()[round.actingSeat()].player().id();
            table.apply(action(actor, ActionType.CHECK, 0));
        }

        assertEquals(5, round.boards().get(0).size());
        assertEquals(5, round.boards().get(1).size());
        assertEquals(Set.of(a), round.winnersByBoard().get(0));
        assertEquals(Set.of(b), round.winnersByBoard().get(1));
        // Ante in, matching share back out on the board each of them actually won - net even.
        assertEquals(startingTotal, findPlayer(table, a).stack() + findPlayer(table, b).stack());
        assertEquals(aStackBefore, findPlayer(table, a).stack());
        assertEquals(bStackBefore, findPlayer(table, b).stack());
    }

    @Test
    void omahaBombPotDealsFourHoleCardsAndUsesTheOmahaRulePerBoard() {
        Table table = new Table(9, GameVariant.TEXAS_HOLDEM, new GameConfig(5, 10, 20));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seatTwoAndFinishHandOne(table, a, b);
        table.apply(nextHand(a));
        table.apply(voteMode(a, GameVariant.OMAHA_BOMB_POT));
        table.apply(voteMode(b, GameVariant.OMAHA_BOMB_POT));
        table.apply(bombPotOpt(a, true));
        table.apply(bombPotOpt(b, true));

        GameRound round = table.currentRound();
        assertEquals(GameVariant.OMAHA_BOMB_POT, table.variant());
        assertEquals(4, round.holeCards().get(a).cards().size());
        assertEquals(4, round.holeCards().get(b).cards().size());
        assertEquals(2, round.boards().size());
    }
}
