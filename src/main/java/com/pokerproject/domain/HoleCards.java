package com.pokerproject.domain;

import java.util.List;

// A List, not fixed fields - hold'em uses 2, Omaha uses 4, so arity is a runtime size,
// not a type change, if a second variant is ever added. Deliberately not named "Hand" -
// see the naming flag in architecture/development-plan.html.
public record HoleCards(List<Card> cards) {
}
