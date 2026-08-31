package com.pokerproject.protocol;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// round is null when no hand is in progress. variant is the mode the CURRENT (or most recent)
// hand is using. votingOpen/votes describe the gap between hands: a player calls NEXT_HAND to
// open votingOpen, casts a vote (playerId -> pick, not secret - same map for every viewer),
// and the moment one is decided the next hand deals immediately and votingOpen flips back off -
// unless it's a bomb pot variant, which opens bombPotOptInOpen instead of dealing right away.
// pendingBombPotVariant/bombPotOptIns describe that window the same way variant/votes describe
// the vote (playerId -> opted in, not secret); the 60s auto-opt-out timeout is wire-layer-only,
// not reflected here - the window just closes (bombPotOptInOpen flips off) when it fires.
public record TableSnapshot(List<SeatSnapshot> seats, int dealerSeat, boolean closed, RoundSnapshot round,
                             GameVariant variant, boolean votingOpen, Map<UUID, GameVariant> votes,
                             boolean bombPotOptInOpen, GameVariant pendingBombPotVariant,
                             Map<UUID, Boolean> bombPotOptIns) {
}
