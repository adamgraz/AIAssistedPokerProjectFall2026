package com.pokerproject.domain;

import com.pokerproject.protocol.ActionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameRound {

    private final Deck deck = new Deck();
    // One board for every variant except a bomb pot's double-board format, which adds a
    // second one in Table.startBombPotHand before any cards are dealt. Index-addressed so
    // showdown/dealing code loops the same way regardless of how many there are.
    private final List<List<Card>> boards = new ArrayList<>(List.of(new ArrayList<>()));
    private final Pot pot = new Pot();
    private final Map<UUID, HoleCards> holeCards = new HashMap<>();
    private RoundStage stage = RoundStage.WAITING;

    private int smallBlindSeat;
    private int bigBlindSeat;

    // Seeded with every active seat when a street starts; an action removes that seat,
    // a bet/raise resets it back to everyone else active. Street is done when this is empty.
    private final Set<UUID> toAct = new HashSet<>();
    private long currentBet;
    private long lastRaiseSize;

    // Which seat is currently on the clock - turn-order enforcement, distinct from toAct
    // (toAct says who still owes a decision; this says whose decision it is right now).
    private int actingSeat = -1;

    // Each player's most recent action on the CURRENT street - reset (cleared) at the start
    // of every street alongside currentBet/lastRaiseSize, so seeing "Bob: Raised" next to a
    // seat always means this street, never a stale leftover from an earlier one. Lets the UI
    // show the street's action sequence (who called, who folded) without a separate signal.
    private final Map<UUID, ActionType> lastActionByPlayer = new HashMap<>();

    // Per-street contribution, reset at the start of every street. Pot.contributions is
    // cumulative across the whole hand, so this is what call/raise math actually needs.
    private final Map<UUID, Long> streetContributions = new HashMap<>();

    // Populated only at a real showdown (never for an uncontested fold win, where hands are
    // never compared), one entry per board - each eligible player's best 5 cards on that
    // board, for UI highlighting. Index-aligned with boards().
    private final List<Map<UUID, List<Card>>> bestFiveByBoard = new ArrayList<>();

    // Everyone who won any share of any board's pot, one Set per board - a Set because a
    // player can win more than one side-pot tier on the same board, or tie for one. Index-
    // aligned with boards(); empty until COMPLETE.
    private final List<Set<UUID>> winnersByBoard = new ArrayList<>();

    public Deck deck() {
        return deck;
    }

    public List<List<Card>> boards() {
        return boards;
    }

    public Pot pot() {
        return pot;
    }

    public Map<UUID, HoleCards> holeCards() {
        return holeCards;
    }

    public RoundStage stage() {
        return stage;
    }

    public void setStage(RoundStage stage) {
        this.stage = stage;
    }

    public int smallBlindSeat() {
        return smallBlindSeat;
    }

    public void setSmallBlindSeat(int smallBlindSeat) {
        this.smallBlindSeat = smallBlindSeat;
    }

    public int bigBlindSeat() {
        return bigBlindSeat;
    }

    public void setBigBlindSeat(int bigBlindSeat) {
        this.bigBlindSeat = bigBlindSeat;
    }

    public Set<UUID> toAct() {
        return toAct;
    }

    public boolean isStreetComplete() {
        return toAct.isEmpty();
    }

    public long currentBet() {
        return currentBet;
    }

    public void setCurrentBet(long currentBet) {
        this.currentBet = currentBet;
    }

    public long lastRaiseSize() {
        return lastRaiseSize;
    }

    public void setLastRaiseSize(long lastRaiseSize) {
        this.lastRaiseSize = lastRaiseSize;
    }

    public int actingSeat() {
        return actingSeat;
    }

    public void setActingSeat(int actingSeat) {
        this.actingSeat = actingSeat;
    }

    public Map<UUID, ActionType> lastActionByPlayer() {
        return lastActionByPlayer;
    }

    public Map<UUID, Long> streetContributions() {
        return streetContributions;
    }

    public long contributionThisStreet(UUID playerId) {
        return streetContributions.getOrDefault(playerId, 0L);
    }

    public List<Map<UUID, List<Card>>> bestFiveByBoard() {
        return bestFiveByBoard;
    }

    public List<Set<UUID>> winnersByBoard() {
        return winnersByBoard;
    }
}
