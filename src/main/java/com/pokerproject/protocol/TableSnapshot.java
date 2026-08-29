package com.pokerproject.protocol;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// round is null when no hand is in progress. variant is the mode the CURRENT (or most recent)
// hand is using. votingOpen/votes describe the gap between hands: a player calls NEXT_HAND to
// open votingOpen, casts a vote (playerId -> pick, not secret - same map for every viewer),
// and the moment one is decided the next hand deals immediately and votingOpen flips back off.
public record TableSnapshot(List<SeatSnapshot> seats, int dealerSeat, boolean closed, RoundSnapshot round,
                             GameVariant variant, boolean votingOpen, Map<UUID, GameVariant> votes) {
}
