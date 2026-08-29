package com.pokerproject.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameRound {

    private final Deck deck = new Deck();
    private final List<Card> board = new ArrayList<>();
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

    // Per-street contribution, reset at the start of every street. Pot.contributions is
    // cumulative across the whole hand, so this is what call/raise math actually needs.
    private final Map<UUID, Long> streetContributions = new HashMap<>();

    // Populated only at a real showdown (never for an uncontested fold win, where hands are
    // never compared) - each eligible player's best 5 of their 7 cards, for UI highlighting.
    private final Map<UUID, List<Card>> bestFiveByPlayer = new HashMap<>();

    // Everyone who won any share of the pot - a Set because a player can win more than one
    // side pot, and more than one player can tie for a single pot. Empty until COMPLETE.
    private final Set<UUID> winners = new HashSet<>();

    public Deck deck() {
        return deck;
    }

    public List<Card> board() {
        return board;
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

    public Map<UUID, Long> streetContributions() {
        return streetContributions;
    }

    public long contributionThisStreet(UUID playerId) {
        return streetContributions.getOrDefault(playerId, 0L);
    }

    public Map<UUID, List<Card>> bestFiveByPlayer() {
        return bestFiveByPlayer;
    }

    public Set<UUID> winners() {
        return winners;
    }
}
