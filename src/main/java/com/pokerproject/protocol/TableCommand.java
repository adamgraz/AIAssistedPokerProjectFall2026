package com.pokerproject.protocol;

// Deliberately separate from ActionType - these are table-management intents, not
// betting actions.
public enum TableCommand {
    SIT_DOWN, LEAVE_TABLE, REBUY, END_TABLE
}
