package com.pokerproject.domain;

import java.util.ArrayList;
import java.util.List;

// Omaha: exactly 2 of the 4 hole cards + exactly 3 of the 5 board cards - unlike Hold'em,
// not "best 5 of however many you've got." Brute-forces all 6x10 combos through the same
// HandEvaluator.classify scorer Hold'em uses.
public final class OmahaFormationRule implements HandFormationRule {

    @Override
    public EvaluatedHand evaluate(List<Card> holeCards, List<Card> board) {
        long best = Long.MIN_VALUE;
        List<Card> bestFive = null;
        for (List<Card> holePair : combinations(holeCards, 2)) {
            for (List<Card> boardTriple : combinations(board, 3)) {
                List<Card> five = new ArrayList<>(holePair);
                five.addAll(boardTriple);
                long score = HandEvaluator.classify(five);
                if (score > best) {
                    best = score;
                    bestFive = five;
                }
            }
        }
        return new EvaluatedHand(best, bestFive);
    }

    @Override
    public int holeCardCount() {
        return 4;
    }

    private static List<List<Card>> combinations(List<Card> cards, int size) {
        List<List<Card>> result = new ArrayList<>();
        combine(cards, size, 0, new ArrayList<>(), result);
        return result;
    }

    private static void combine(List<Card> cards, int size, int start, List<Card> current, List<List<Card>> result) {
        if (current.size() == size) {
            result.add(List.copyOf(current));
            return;
        }
        for (int i = start; i < cards.size(); i++) {
            current.add(cards.get(i));
            combine(cards, size, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
}
