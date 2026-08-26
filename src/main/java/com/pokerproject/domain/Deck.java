package com.pokerproject.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Deck {

    private final List<Card> cards = new ArrayList<>(52);

    public Deck() {
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(new Card(rank, suit));
            }
        }
    }

    public void shuffle(SecureRandom random) {
        Collections.shuffle(cards, random);
    }

    public Card draw() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("deck is empty");
        }
        return cards.remove(cards.size() - 1);
    }

    public int remaining() {
        return cards.size();
    }

    // Test support: force the remaining cards to a known order, so a scripted hand's
    // outcome is deterministic instead of depending on a real shuffle. cardsInDrawOrder[0]
    // is what the next draw() call returns.
    void stack(List<Card> cardsInDrawOrder) {
        cards.clear();
        for (int i = cardsInDrawOrder.size() - 1; i >= 0; i--) {
            cards.add(cardsInDrawOrder.get(i));
        }
    }
}
