package com.pokerproject.protocol;

import com.pokerproject.domain.Card;
import com.pokerproject.domain.RoundStage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// yourHoleCards: the viewer's own hand, empty if they weren't dealt into this round.
// revealedHoleCards: every OTHER player's hand, populated only once stage is COMPLETE and
// that player didn't fold - hidden during betting, same as a real table.
// yourStreetContribution: how much the viewer has put in on the CURRENT street - lets the
// wire client compute currentBet - yourStreetContribution ("owed") to grey out Fold (owed
// == 0, nothing to call) and Check (owed > 0, facing a bet) without duplicating Table's rules.
// bestFive: each showdown-eligible player's winning 5 cards, for UI highlighting - populated
// only at a real showdown (COMPLETE after resolveShowdown), empty for an uncontested fold win
// where hands are never compared.
// winners: everyone who won any share of the pot - more than one playerId when a side pot
// went to someone else, or a pot was split on a tie. Empty until COMPLETE.
public record RoundSnapshot(RoundStage stage, List<Card> board, long pot, int smallBlindSeat,
                             int bigBlindSeat, int actingSeat, long currentBet,
                             List<Card> yourHoleCards, Map<UUID, List<Card>> revealedHoleCards,
                             long yourStreetContribution, Map<UUID, List<Card>> bestFive,
                             Set<UUID> winners) {
}
