package com.pokerproject.protocol;

// Players vote on this each hand (Table.handleVoteGameMode). Which cards a hand can be built
// from is a HandFormationRule (Table.formationRuleFor) - not part of this enum, since the same
// rule is reused across variants that share it: the two BOMB_POT variants reuse the same
// formation rule as their normal counterpart, just with a different deal/betting structure
// (Table.isBombPot) - double board, ante-only, no preflop betting, opt-in before the deal.
public enum GameVariant {
    TEXAS_HOLDEM,
    OMAHA,
    TEXAS_BOMB_POT,
    OMAHA_BOMB_POT
}
