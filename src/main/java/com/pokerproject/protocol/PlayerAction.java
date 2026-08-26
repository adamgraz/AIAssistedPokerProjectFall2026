package com.pokerproject.protocol;

import java.util.UUID;

public record PlayerAction(UUID playerId, ActionType type, long amount) {
}
