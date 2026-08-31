package com.pokerproject.domain;

import java.util.ArrayList;
import java.util.List;

public final class HandEvaluator {

    private HandEvaluator() {
    }

    public static EvaluatedHand evaluate(List<Card> sevenCards) {
        long best = Long.MIN_VALUE;
        List<Card> bestFive = null;
        for (List<Card> five : fiveCardCombinations(sevenCards)) {
            long score = classify(five);
            if (score > best) {
                best = score;
                bestFive = five;
            }
        }
        return new EvaluatedHand(best, bestFive);
    }

    private static List<List<Card>> fiveCardCombinations(List<Card> cards) {
        List<List<Card>> result = new ArrayList<>();
        int n = cards.size();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                for (int c = b + 1; c < n; c++) {
                    for (int d = c + 1; d < n; d++) {
                        for (int e = d + 1; e < n; e++) {
                            result.add(List.of(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)));
                        }
                    }
                }
            }
        }
        return result;
    }

    static long classify(List<Card> five) {
        int[] countByRank = new int[13];
        for (Card card : five) {
            countByRank[card.rank().ordinal()]++;
        }

        boolean isFlush = five.stream().map(Card::suit).distinct().count() == 1;

        List<Integer> distinctRanks = new ArrayList<>();
        for (int r = 0; r < 13; r++) {
            if (countByRank[r] > 0) distinctRanks.add(r);
        }
        boolean isWheel = distinctRanks.equals(List.of(0, 1, 2, 3, 12)); // A-2-3-4-5, ace plays low
        boolean isStraight = distinctRanks.size() == 5
                && (distinctRanks.get(4) - distinctRanks.get(0) == 4 || isWheel);
        int straightHigh = isWheel ? Rank.FIVE.ordinal() : (isStraight ? distinctRanks.get(4) : -1);

        // Ranks ordered by (count desc, rank desc) - built rank-descending then stably
        // sorted by count, so equal-count ranks keep their rank-descending order for free.
        List<Integer> ranksByGroup = new ArrayList<>();
        for (int r = 12; r >= 0; r--) {
            if (countByRank[r] > 0) ranksByGroup.add(r);
        }
        ranksByGroup.sort((r1, r2) -> countByRank[r2] - countByRank[r1]);
        List<Integer> groupSizes = ranksByGroup.stream().map(r -> countByRank[r]).toList();

        if (isStraight && isFlush) {
            return pack(HandRank.STRAIGHT_FLUSH, straightHigh);
        }
        if (groupSizes.equals(List.of(4, 1))) {
            return pack(HandRank.FOUR_OF_A_KIND, ranksByGroup.get(0), ranksByGroup.get(1));
        }
        if (groupSizes.equals(List.of(3, 2))) {
            return pack(HandRank.FULL_HOUSE, ranksByGroup.get(0), ranksByGroup.get(1));
        }
        if (isFlush) {
            return pack(HandRank.FLUSH, kickers(ranksByGroup, 5));
        }
        if (isStraight) {
            return pack(HandRank.STRAIGHT, straightHigh);
        }
        if (groupSizes.equals(List.of(3, 1, 1))) {
            return pack(HandRank.THREE_OF_A_KIND, kickers(ranksByGroup, 3));
        }
        if (groupSizes.equals(List.of(2, 2, 1))) {
            return pack(HandRank.TWO_PAIR, kickers(ranksByGroup, 3));
        }
        if (groupSizes.equals(List.of(2, 1, 1, 1))) {
            return pack(HandRank.ONE_PAIR, kickers(ranksByGroup, 4));
        }
        return pack(HandRank.HIGH_CARD, kickers(ranksByGroup, 5));
    }

    private static int[] kickers(List<Integer> ranksByGroup, int count) {
        return ranksByGroup.subList(0, count).stream().mapToInt(Integer::intValue).toArray();
    }

    private static long pack(HandRank category, int... kickers) {
        long value = (long) category.ordinal() << 24;
        int shift = 20;
        for (int kicker : kickers) {
            value |= (long) kicker << shift;
            shift -= 4;
        }
        return value;
    }
}
