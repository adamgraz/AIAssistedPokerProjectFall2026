package com.pokerproject.domain;

import java.util.UUID;

public final class Player {

    private final UUID id;
    private final String displayName;
    private long stack;
    private long totalBuyIn;
    private PlayerStatus status;

    // Doesn't exist until SIT_DOWN is processed - id is the connection's already-assigned
    // UUID, displayName and buy-in amount come straight off that request's payload.
    public Player(UUID id, String displayName, long buyIn) {
        this.id = id;
        this.displayName = displayName;
        this.stack = buyIn;
        this.totalBuyIn = buyIn;
        this.status = PlayerStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public long stack() {
        return stack;
    }

    public long totalBuyIn() {
        return totalBuyIn;
    }

    // stack - totalBuyIn at any moment is this player's session profit.
    public long profit() {
        return stack - totalBuyIn;
    }

    public PlayerStatus status() {
        return status;
    }

    public void setStatus(PlayerStatus status) {
        this.status = status;
    }

    public void addToStack(long amount) {
        this.stack += amount;
    }

    public void removeFromStack(long amount) {
        this.stack -= amount;
    }

    public void rebuy(long amount) {
        this.stack += amount;
        this.totalBuyIn += amount;
    }
}
