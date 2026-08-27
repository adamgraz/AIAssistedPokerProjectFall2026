package com.pokerproject.protocol;

// player is null if the seat is empty.
public record SeatSnapshot(int index, PlayerSnapshot player) {
}
