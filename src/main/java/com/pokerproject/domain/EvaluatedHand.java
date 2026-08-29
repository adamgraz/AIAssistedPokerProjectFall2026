package com.pokerproject.domain;

import java.util.List;

// Category and kickers packed into one comparable long: category at bit 24, each kicker
// in its own 4-bit slot below it. A higher category always outweighs any kicker combination
// below it, and a higher kicker always outweighs anything in a lower-priority slot, so plain
// long comparison is the entire tiebreak — see architecture/development-plan.html for the worked example.
// cards: the specific 5 (of the 7 available) that produced this value - for UI highlighting.
public record EvaluatedHand(long value, List<Card> cards) implements Comparable<EvaluatedHand> {

    @Override
    public int compareTo(EvaluatedHand other) {
        return Long.compare(this.value, other.value);
    }
}
