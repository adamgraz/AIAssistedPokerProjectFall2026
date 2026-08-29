package com.pokerproject.protocol;

// Deliberately separate from ActionType - these are table-management intents, not
// betting actions.
public enum TableCommand {
    SIT_DOWN, LEAVE_TABLE, REBUY, END_TABLE, VOTE_GAME_MODE, NEXT_HAND, REMOVE_PLAYER
}
