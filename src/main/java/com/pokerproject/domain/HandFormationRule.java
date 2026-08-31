package com.pokerproject.domain;

import java.util.List;

// The one rule that differs per variant: which of a player's hole cards + which board cards
// are even allowed to combine into a hand. Everything else (betting, pots, blinds) is the
// same machinery regardless of which rule is plugged in here.
public interface HandFormationRule {
    EvaluatedHand evaluate(List<Card> holeCards, List<Card> board);

    int holeCardCount();
}
