package com.pokerproject.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotTest {

    @Test
    void foldedContributionStillCountsTowardTotalButNotEligibility() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        Pot pot = new Pot();
        pot.contribute(a, 50); // folds after this
        pot.contribute(b, 100);
        pot.contribute(c, 100);

        List<Pot.SidePot> pots = pot.resolve(Set.of(a));

        long totalAwarded = pots.stream().mapToLong(Pot.SidePot::amount).sum();
        assertEquals(250, totalAwarded); // A's 50 must still be in there somewhere

        for (Pot.SidePot sidePot : pots) {
            assertTrue(sidePot.eligiblePlayers().stream().noneMatch(p -> p.equals(a)));
        }
    }

    @Test
    void genuineSidePotKeepsShortStackOutOfTheExtraLayer() {
        UUID a = UUID.randomUUID(); // short all-in, still active
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        Pot pot = new Pot();
        pot.contribute(a, 40);
        pot.contribute(b, 100);
        pot.contribute(c, 100);

        List<Pot.SidePot> pots = pot.resolve(Set.of()); // nobody folded

        assertEquals(240, pots.stream().mapToLong(Pot.SidePot::amount).sum());
        assertEquals(2, pots.size());
        assertEquals(Set.of(a, b, c), pots.get(0).eligiblePlayers()); // main pot: everyone
        assertEquals(Set.of(b, c), pots.get(1).eligiblePlayers());    // side pot: A can't reach it
    }
}
