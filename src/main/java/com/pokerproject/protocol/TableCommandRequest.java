package com.pokerproject.protocol;

import java.util.UUID;

// displayName is only meaningful for SIT_DOWN; amount is meaningful for SIT_DOWN (buy-in)
// and REBUY; gameVariant (a GameVariant enum name) is only meaningful for VOTE_GAME_MODE;
// targetPlayerId (a UUID string) is only meaningful for REMOVE_PLAYER - the seat being kicked,
// distinct from playerId which is always the requester. Unused fields are just null/zero for
// commands that don't need them - same sparse-payload shape as PlayerAction.
public record TableCommandRequest(UUID playerId, TableCommand command, String displayName, long amount,
                                   String gameVariant, String targetPlayerId) {
}
