package com.pokerproject.domain;

// Ordinal order is the strength order. No separate ROYAL_FLUSH: it's just the ace-high
// straight flush, which already sorts correctly via its packed kicker without a distinct category.
public enum HandRank {
    HIGH_CARD, ONE_PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH
}
