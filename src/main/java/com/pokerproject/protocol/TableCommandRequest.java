package com.pokerproject.protocol;

import java.util.UUID;

// displayName is only meaningful for SIT_DOWN; amount is meaningful for SIT_DOWN (buy-in)
// and REBUY. Unused fields are just null/zero for commands that don't need them - same
// sparse-payload shape as PlayerAction.
public record TableCommandRequest(UUID playerId, TableCommand command, String displayName, long amount) {
}
