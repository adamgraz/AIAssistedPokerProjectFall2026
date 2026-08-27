package com.pokerproject.protocol;

import com.pokerproject.domain.Card;
import com.pokerproject.domain.RoundStage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// yourHoleCards: the viewer's own hand, empty if they weren't dealt into this round.
// revealedHoleCards: every OTHER player's hand, populated only once stage is COMPLETE and
// that player didn't fold - hidden during betting, same as a real table.
public record RoundSnapshot(RoundStage stage, List<Card> board, long pot, int smallBlindSeat,
                             int bigBlindSeat, int actingSeat, long currentBet,
                             List<Card> yourHoleCards, Map<UUID, List<Card>> revealedHoleCards) {
}
