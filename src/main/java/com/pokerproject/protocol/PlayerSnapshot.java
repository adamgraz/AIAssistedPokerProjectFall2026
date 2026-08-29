package com.pokerproject.protocol;

import com.pokerproject.domain.PlayerStatus;

import java.util.UUID;

// connected: whether this player's connection is still open right now - false means their
// seat is orphaned (browser closed, no reconnect built yet) and any seated player can issue
// REMOVE_PLAYER against them; a live player can never be targeted, so this is the only case
// that command accepts.
public record PlayerSnapshot(UUID id, String displayName, long stack, long totalBuyIn, PlayerStatus status,
                              boolean connected) {
}
