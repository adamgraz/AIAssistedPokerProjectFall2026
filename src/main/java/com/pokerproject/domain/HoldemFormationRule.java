package com.pokerproject.domain;

import java.util.ArrayList;
import java.util.List;

// Hold'em: best 5 of however many cards you've got (2 hole + up to 5 board), no restriction
// on how many come from each pile.
public final class HoldemFormationRule implements HandFormationRule {

    @Override
    public EvaluatedHand evaluate(List<Card> holeCards, List<Card> board) {
        List<Card> allCards = new ArrayList<>(holeCards);
        allCards.addAll(board);
        return HandEvaluator.evaluate(allCards);
    }

    @Override
    public int holeCardCount() {
        return 2;
    }
}
