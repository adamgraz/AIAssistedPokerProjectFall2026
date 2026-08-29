package com.pokerproject.protocol;

// TEXAS_HOLDEM only for now - players vote on this each hand (Table.handleVoteGameMode),
// but with a single possible value the vote always clinches instantly. No engine interface
// for actually running a second variant's rules exists yet - see architecture/development-plan.html.
public enum GameVariant {
    TEXAS_HOLDEM
}
