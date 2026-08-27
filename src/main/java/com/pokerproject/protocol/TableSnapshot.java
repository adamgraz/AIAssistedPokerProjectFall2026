package com.pokerproject.protocol;

import java.util.List;

// round is null when no hand is in progress.
public record TableSnapshot(List<SeatSnapshot> seats, int dealerSeat, boolean closed, RoundSnapshot round) {
}
