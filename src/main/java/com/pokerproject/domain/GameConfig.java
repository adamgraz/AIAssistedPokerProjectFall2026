package com.pokerproject.domain;

// Loaded once at startup from a small properties file. Change a value, restart the
// instance - no live reload. No stack/buy-in field: buy-in is player-chosen per
// SIT_DOWN/REBUY, not a fixed table-wide number. bombPotAnte: what each opted-in player
// posts for a bomb pot hand - independent of smallBlind/bigBlind, not derived from them.
public record GameConfig(long smallBlind, long bigBlind, long bombPotAnte) {
}
