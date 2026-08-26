package com.pokerproject.domain;

// Category and kickers packed into one comparable long: category at bit 24, each kicker
// in its own 4-bit slot below it. A higher category always outweighs any kicker combination
// below it, and a higher kicker always outweighs anything in a lower-priority slot, so plain
// long comparison is the entire tiebreak — see architecture/development-plan.html for the worked example.
public record EvaluatedHand(long value) implements Comparable<EvaluatedHand> {

    @Override
    public int compareTo(EvaluatedHand other) {
        return Long.compare(this.value, other.value);
    }
}
