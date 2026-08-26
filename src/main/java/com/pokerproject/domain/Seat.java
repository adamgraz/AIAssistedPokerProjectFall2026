package com.pokerproject.domain;

public final class Seat {

    private final int index;
    private Player player; // null if empty

    public Seat(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }

    public Player player() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public boolean isEmpty() {
        return player == null;
    }
}
