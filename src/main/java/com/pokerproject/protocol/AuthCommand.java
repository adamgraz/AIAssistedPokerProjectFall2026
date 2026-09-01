package com.pokerproject.protocol;

// Separate from TableCommand on purpose - Table has no notion of profiles or passphrases at
// all, so these never reach Table.apply(...). PokerServer resolves them directly against the
// database and just swaps this connection's identity in its own connections map.
public enum AuthCommand {
    LOGIN, CREATE_PROFILE
}
