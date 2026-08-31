package com.pokerproject.protocol;

import com.pokerproject.domain.Card;
import com.pokerproject.domain.RoundStage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// boards: one board for every variant except a bomb pot's double-board format, which has two -
// index-aligned with bestFiveByBoard/winnersByBoard below.
// yourHoleCards: the viewer's own hand, empty if they weren't dealt into this round.
// revealedHoleCards: every player who actually reached a real showdown (their hand was
// compared - see SnapshotBuilder), keyed by playerId. Empty for an uncontested fold win, even
// for the winner - nobody's cards get shown just because everyone else folded, same as a real
// table. Populated only once stage is COMPLETE; hidden during betting either way.
// yourStreetContribution: how much the viewer has put in on the CURRENT street - lets the
// wire client compute currentBet - yourStreetContribution ("owed") to grey out Fold (owed
// == 0, nothing to call) and Check (owed > 0, facing a bet) without duplicating Table's rules.
// lastRaiseSize: the current street's minimum raise increment - a legal raise must reach at
// least currentBet + lastRaiseSize, what a "you're facing a bet" panel needs without
// duplicating Table's own validation math on the client.
// lastActionByPlayer: each player's most recent action on the CURRENT street only - cleared
// the moment a new street starts, so a seat's "Raised"/"Called"/"Folded" tag never shows a
// previous street's stale action.
// bestFiveByBoard: each showdown-eligible player's winning 5 cards on that board, for UI
// highlighting - populated only at a real showdown (COMPLETE after resolveShowdown), empty
// for an uncontested fold win where hands are never compared.
// winnersByBoard: everyone who won any share of that board's pot - more than one playerId
// when a side pot went to someone else, or a pot was split on a tie. Empty until COMPLETE.
public record RoundSnapshot(RoundStage stage, List<List<Card>> boards, long pot, int smallBlindSeat,
                             int bigBlindSeat, int actingSeat, long currentBet,
                             List<Card> yourHoleCards, Map<UUID, List<Card>> revealedHoleCards,
                             long yourStreetContribution, long lastRaiseSize,
                             Map<UUID, ActionType> lastActionByPlayer,
                             List<Map<UUID, List<Card>>> bestFiveByBoard,
                             List<Set<UUID>> winnersByBoard) {
}
