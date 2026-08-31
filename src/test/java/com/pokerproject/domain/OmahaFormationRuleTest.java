package com.pokerproject.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OmahaFormationRuleTest {

    private static Card c(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    @Test
    void mustUseExactlyTwoHoleCardsNotBestFiveOfSeven() {
        // Board alone is a straight (2-3-4-5-6), but Omaha forbids a 0-hole-card hand -
        // if this rule just did Hold'em's "best 5 of everything", it would wrongly find
        // that straight. With four aces in the hole, the real best is exactly 2 aces +
        // 3 board cards - just one pair.
        List<Card> holeCards = List.of(
                c(Rank.ACE, Suit.SPADES), c(Rank.ACE, Suit.HEARTS),
                c(Rank.ACE, Suit.DIAMONDS), c(Rank.ACE, Suit.CLUBS));
        List<Card> board = List.of(
                c(Rank.TWO, Suit.CLUBS), c(Rank.THREE, Suit.DIAMONDS), c(Rank.FOUR, Suit.HEARTS),
                c(Rank.FIVE, Suit.SPADES), c(Rank.SIX, Suit.DIAMONDS));

        EvaluatedHand result = new OmahaFormationRule().evaluate(holeCards, board);

        int category = (int) (result.value() >> 24);
        assertEquals(HandRank.ONE_PAIR.ordinal(), category);
        assertEquals(2, result.cards().stream().filter(holeCards::contains).count());
        assertEquals(3, result.cards().stream().filter(board::contains).count());
    }
}
