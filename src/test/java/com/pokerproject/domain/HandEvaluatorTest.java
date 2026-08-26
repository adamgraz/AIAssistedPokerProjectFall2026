package com.pokerproject.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandEvaluatorTest {

    private static Card c(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    @Test
    void fourOfAKindBeatsFullHouse() {
        EvaluatedHand quads = HandEvaluator.evaluate(List.of(
                c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS), c(Rank.ACE, Suit.HEARTS), c(Rank.ACE, Suit.SPADES),
                c(Rank.TWO, Suit.CLUBS), c(Rank.THREE, Suit.DIAMONDS), c(Rank.FOUR, Suit.HEARTS)));

        EvaluatedHand fullHouse = HandEvaluator.evaluate(List.of(
                c(Rank.KING, Suit.CLUBS), c(Rank.KING, Suit.DIAMONDS), c(Rank.KING, Suit.HEARTS),
                c(Rank.SEVEN, Suit.CLUBS), c(Rank.SEVEN, Suit.DIAMONDS), c(Rank.TWO, Suit.HEARTS), c(Rank.THREE, Suit.SPADES)));

        assertTrue(quads.compareTo(fullHouse) > 0);
    }

    @Test
    void fullHouseTripsRankDecidesOverPairRank() {
        // Kings-over-Sevens must beat Queens-over-Aces: trips rank outranks pair rank,
        // regardless of which full house has the "bigger" pair.
        EvaluatedHand kingsOverSevens = HandEvaluator.evaluate(List.of(
                c(Rank.KING, Suit.CLUBS), c(Rank.KING, Suit.DIAMONDS), c(Rank.KING, Suit.HEARTS),
                c(Rank.SEVEN, Suit.CLUBS), c(Rank.SEVEN, Suit.DIAMONDS), c(Rank.TWO, Suit.HEARTS), c(Rank.THREE, Suit.SPADES)));

        EvaluatedHand queensOverAces = HandEvaluator.evaluate(List.of(
                c(Rank.QUEEN, Suit.CLUBS), c(Rank.QUEEN, Suit.DIAMONDS), c(Rank.QUEEN, Suit.HEARTS),
                c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS), c(Rank.TWO, Suit.HEARTS), c(Rank.THREE, Suit.SPADES)));

        assertTrue(kingsOverSevens.compareTo(queensOverAces) > 0);
    }

    @Test
    void sixHighStraightBeatsWheel() {
        EvaluatedHand wheel = HandEvaluator.evaluate(List.of(
                c(Rank.ACE, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS),
                c(Rank.FOUR, Suit.SPADES), c(Rank.FIVE, Suit.CLUBS), c(Rank.NINE, Suit.DIAMONDS), c(Rank.JACK, Suit.HEARTS)));

        EvaluatedHand sixHigh = HandEvaluator.evaluate(List.of(
                c(Rank.TWO, Suit.CLUBS), c(Rank.THREE, Suit.DIAMONDS), c(Rank.FOUR, Suit.HEARTS),
                c(Rank.FIVE, Suit.SPADES), c(Rank.SIX, Suit.CLUBS), c(Rank.NINE, Suit.DIAMONDS), c(Rank.JACK, Suit.HEARTS)));

        assertTrue(sixHigh.compareTo(wheel) > 0);
    }

    @Test
    void straightFlushBeatsFlush() {
        EvaluatedHand straightFlush = HandEvaluator.evaluate(List.of(
                c(Rank.FIVE, Suit.CLUBS), c(Rank.SIX, Suit.CLUBS), c(Rank.SEVEN, Suit.CLUBS),
                c(Rank.EIGHT, Suit.CLUBS), c(Rank.NINE, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS)));

        EvaluatedHand flush = HandEvaluator.evaluate(List.of(
                c(Rank.TWO, Suit.CLUBS), c(Rank.FIVE, Suit.CLUBS), c(Rank.SEVEN, Suit.CLUBS),
                c(Rank.NINE, Suit.CLUBS), c(Rank.JACK, Suit.CLUBS), c(Rank.THREE, Suit.DIAMONDS), c(Rank.FOUR, Suit.HEARTS)));

        assertTrue(straightFlush.compareTo(flush) > 0);
    }

    @Test
    void twoPairSecondPairRankOutranksHigherKicker() {
        // Aces-and-Kings (kicker Queen) must beat Aces-and-Queens (kicker King):
        // the second pair rank is compared before the kicker.
        EvaluatedHand acesAndKings = HandEvaluator.evaluate(List.of(
                c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS), c(Rank.KING, Suit.HEARTS), c(Rank.KING, Suit.SPADES),
                c(Rank.QUEEN, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS)));

        EvaluatedHand acesAndQueens = HandEvaluator.evaluate(List.of(
                c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS), c(Rank.QUEEN, Suit.HEARTS), c(Rank.QUEEN, Suit.SPADES),
                c(Rank.KING, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS)));

        assertTrue(acesAndKings.compareTo(acesAndQueens) > 0);
    }

    @Test
    void bestOfSevenPicksFlushOverThreeOfAKind() {
        EvaluatedHand result = HandEvaluator.evaluate(List.of(
                c(Rank.KING, Suit.CLUBS), c(Rank.KING, Suit.DIAMONDS), c(Rank.KING, Suit.HEARTS),
                c(Rank.TWO, Suit.CLUBS), c(Rank.FIVE, Suit.CLUBS), c(Rank.NINE, Suit.CLUBS), c(Rank.JACK, Suit.CLUBS)));

        int category = (int) (result.value() >> 24);
        assertEquals(HandRank.FLUSH.ordinal(), category);
    }

    @Test
    void evaluationIsDeterministic() {
        List<Card> cards = List.of(
                c(Rank.ACE, Suit.CLUBS), c(Rank.KING, Suit.DIAMONDS), c(Rank.QUEEN, Suit.HEARTS),
                c(Rank.JACK, Suit.SPADES), c(Rank.TEN, Suit.CLUBS), c(Rank.TWO, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS));

        assertEquals(HandEvaluator.evaluate(cards), HandEvaluator.evaluate(cards));
    }
}
